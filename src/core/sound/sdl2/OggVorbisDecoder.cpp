/* SPDX-License-Identifier: MIT */
/* Native Ogg Vorbis decoder for platforms that cannot load wuvorbis.dll. */

#include "tjsCommHead.h"

#include <cassert>
#include <climits>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <utility>
#include <vector>

#include "StorageIntf.h"
#include "DebugIntf.h"
#include "WaveIntf.h"

#if defined(_MSC_VER)
#include <malloc.h>
#define TVP_OGG_ALLOCA _alloca
#else
#include <alloca.h>
#define TVP_OGG_ALLOCA alloca
#endif

#ifndef FAUDIOAPI
#define FAUDIOAPI
#endif
#define FAudio_alloca TVP_OGG_ALLOCA
#define FAudio_dealloca(pointer) ((void)(pointer))
#define STB_VORBIS_NO_PUSHDATA_API 1
#define STB_VORBIS_NO_STDIO 1
#include "stb_vorbis.h"

class tTVPOggVorbisDecoder final : public tTVPWaveDecoder
{
	std::vector<unsigned char> Data;
	stb_vorbis *Decoder = nullptr;
	tTVPWaveFormat Format{};

public:
	explicit tTVPOggVorbisDecoder(std::vector<unsigned char> &&data)
		: Data(std::move(data))
	{
		int error = 0;
		Decoder = stb_vorbis_open_memory(Data.data(),
			static_cast<int>(Data.size()), &error, nullptr);
		if(!Decoder) return;

		const stb_vorbis_info info = stb_vorbis_get_info(Decoder);
		Format.SamplesPerSec = info.sample_rate;
		Format.Channels = static_cast<tjs_uint>(info.channels);
		Format.BitsPerSample = 16;
		Format.BytesPerSample = 2;
		Format.TotalSamples = stb_vorbis_stream_length_in_samples(Decoder);
		Format.TotalTime = Format.SamplesPerSec == 0 ? 0 :
			Format.TotalSamples * 1000 / Format.SamplesPerSec;
		Format.SpeakerConfig = 0;
		Format.IsFloat = false;
		Format.Seekable = true;
	}

	~tTVPOggVorbisDecoder() override
	{
		if(Decoder) stb_vorbis_close(Decoder);
	}

	bool IsValid() const
	{
		return Decoder && Format.SamplesPerSec > 0 && Format.Channels > 0;
	}

	void GetFormat(tTVPWaveFormat &format) override
	{
		format = Format;
	}

	bool Render(void *buffer, tjs_uint bufferSampleLength,
		tjs_uint &rendered) override
	{
		if(!Decoder || bufferSampleLength == 0)
		{
			rendered = 0;
			return false;
		}

		const tjs_uint64 requestedValues =
			static_cast<tjs_uint64>(bufferSampleLength) * Format.Channels;
		if(requestedValues > static_cast<tjs_uint64>(INT_MAX))
		{
			rendered = 0;
			return false;
		}

		const int samples = stb_vorbis_get_samples_short_interleaved(
			Decoder, static_cast<int>(Format.Channels),
			static_cast<short *>(buffer), static_cast<int>(requestedValues));
		rendered = samples > 0 ? static_cast<tjs_uint>(samples) : 0;
		return rendered == bufferSampleLength;
	}

	bool SetPosition(tjs_uint64 samplePosition) override
	{
		if(!Decoder || samplePosition > UINT_MAX) return false;
		return 0 != stb_vorbis_seek(Decoder,
			static_cast<unsigned int>(samplePosition));
	}
};

class tTVPOggVorbisDecoderCreator final : public tTVPWaveDecoderCreator
{
public:
	tTVPWaveDecoder *Create(const ttstr &storageName,
		const ttstr &extension) override
	{
		static bool firstCreateLogged = false;
		if(!firstCreateLogged)
		{
			TVPAddLog(ttstr(TJS_W("(info) Native audio decoder request: ")) +
				storageName + TJS_W(" [") + extension + TJS_W("]"));
			firstCreateLogged = true;
		}
		if(extension != TJS_W(".ogg")) return nullptr;

		try
		{
			std::unique_ptr<tTJSBinaryStream> stream(TVPCreateStream(storageName));
			if(!stream) return nullptr;
			const tjs_uint64 size = stream->GetSize();
			if(size == 0 || size > static_cast<tjs_uint64>(INT_MAX)) return nullptr;

			std::vector<unsigned char> data(static_cast<size_t>(size));
			stream->ReadBuffer(data.data(), static_cast<tjs_uint>(size));
			std::unique_ptr<tTVPOggVorbisDecoder> decoder(
				new tTVPOggVorbisDecoder(std::move(data)));
			if(!decoder->IsValid())
			{
				TVPAddLog(ttstr(TJS_W("(error) Native Ogg Vorbis decode failed: ")) +
					storageName);
				return nullptr;
			}
			static bool activationLogged = false;
			if(!activationLogged)
			{
				TVPAddLog(TJS_W("(info) Native Ogg Vorbis decoder active."));
				activationLogged = true;
			}
			return decoder.release();
		}
		catch(...)
		{
			TVPAddLog(ttstr(TJS_W("(error) Native Ogg Vorbis could not open: ")) +
				storageName);
			return nullptr;
		}
	}
};

static tTVPOggVorbisDecoderCreator TVPOggVorbisDecoderCreator;

tTVPWaveDecoder *TVPCreateOggVorbisDecoder(const ttstr &storageName)
{
	return TVPOggVorbisDecoderCreator.Create(storageName, TJS_W(".ogg"));
}
