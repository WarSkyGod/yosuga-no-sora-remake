import entryNapi from 'libentry.so';
import { common } from '@kit.AbilityKit';
import { fileIo as fs, picker } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { zlib } from '@ohos.zlib';
import { util } from '@kit.ArkTS';

/** data-assets.json entry. */
interface DataAsset {
  name: string;
  size: number;
  sha256: string;
  packs: string[];
  fileCount: number;
}

/** data-assets.json root, shipped as rawfile by the CI workflow. */
interface DataManifest {
  schemaVersion: number;
  releaseTag: string;
  baseUrl: string;
  assets: DataAsset[];
}

const APP_FOLDER = '缘之空高清重制';
const DOWNLOAD_ROOT = '/storage/Users/currentUser/Download';

@Entry
@Component
struct Index {
  // 0 checking, 1 ready, 2 need data, 3 downloading, 4 importing, 5 error, 6 running
  @State bootState: number = 0;
  @State progressText: string = '';
  @State progressValue: number = 0;
  @State baseUrl: string = '';
  @State message: string = '';
  @State gameKey: string = '0';
  private sandboxFiles: string = '';
  private baseDir: string = '';
  private saveDir: string = '';
  private appDir: string = '';
  private dataDir: string = '';
  private assets: DataAsset[] = [];
  private manifest: DataManifest | null = null;

  aboutToAppear(): void {
    this.sandboxFiles = (getContext(this) as common.UIAbilityContext).filesDir;
    this.prepare();
  }

  async prepare(): Promise<void> {
    this.bootState = 0;
    this.progressText = '正在检查游戏数据…';
    // Prefer the public Download app folder; fall back to the sandbox.
    this.appDir = DOWNLOAD_ROOT + '/' + APP_FOLDER;
    let publicOk = false;
    try {
      await fs.access(this.appDir);
      publicOk = true;
    } catch (e) {
      try {
        fs.mkdirSync(this.appDir, true);
        publicOk = true;
      } catch (e2) {
        publicOk = false;
      }
    }
    this.baseDir = publicOk ? this.appDir : this.sandboxFiles;
    this.saveDir = publicOk ? this.appDir + '/savedata' : this.sandboxFiles + '/savedata';
    this.dataDir = this.baseDir + '/data';

    try {
      await fs.access(this.dataDir + '/startup.tjs');
      this.bootState = 1;
      this.progressText = '游戏数据已就绪';
      return;
    } catch (e) {
      // data missing
    }
    await this.loadManifest();
    this.bootState = 2;
    this.progressText = '需要下载或导入游戏数据';
  }

  async loadManifest(): Promise<void> {
    try {
      const manager = (getContext(this) as common.UIAbilityContext).resourceManager;
      const bytes = await manager.getRawFileContent('data-assets.json');
      const text = new util.TextDecoder('utf-8').decodeWithStream(bytes);
      const parsed = JSON.parse(text) as DataManifest;
      this.manifest = parsed;
      this.baseUrl = parsed.baseUrl;
      this.assets = parsed.assets;
    } catch (e) {
      this.message = '读取数据清单失败，请手动设置下载地址';
    }
  }

  async downloadAll(): Promise<void> {
    if (this.assets.length === 0) {
      this.message = '下载清单为空，请检查网络或稍后重试';
      return;
    }
    this.bootState = 3;
    this.message = '';
    const cacheDir = this.sandboxFiles + '/cache';
    try {
      fs.mkdirSync(cacheDir, true);
    } catch (e) {
      // already exists
    }
    let doneBytes = 0;
    const totalBytes = this.assets.reduce((sum, asset) => sum + asset.size, 0);
    for (const asset of this.assets) {
      const url = this.baseUrl + '/' + asset.name;
      this.progressText = '正在下载 ' + asset.name;
      const tmp = cacheDir + '/' + asset.name;
      try {
        await this.downloadFile(url, tmp);
      } catch (e) {
        this.bootState = 5;
        this.message = '下载失败：' + asset.name + '（' + JSON.stringify(e) + '）';
        return;
      }
      await this.unzipTo(tmp, this.baseDir);
      try {
        fs.unlinkSync(tmp);
      } catch (e) {
        // ignore
      }
      doneBytes += asset.size;
      this.progressValue = Math.min(99, Math.floor(doneBytes * 100 / Math.max(1, totalBytes)));
      await this.delay(50);
    }
    await this.finishData();
  }

  async downloadFile(url: string, dest: string): Promise<void> {
    const httpRequest = http.createHttp();
    let file = -1;
    try {
      file = fs.openSync(dest, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE);
      await new Promise<void>((resolve, reject) => {
        httpRequest.on('dataReceive', (data: ArrayBuffer) => {
          try {
            fs.writeSync(file, data);
          } catch (e) {
            reject(e);
          }
        });
        httpRequest.on('dataReceiveError', () => {
          reject(new Error('http receive error'));
        });
        httpRequest.request(url, {
          method: http.RequestMethod.GET,
          expectDataType: http.HttpDataType.ARRAY_BUFFER,
          timeout: 600000,
          readTimeout: 600000
        }).then((response: http.HttpResponse) => {
          if (response.responseCode !== 200) {
            reject(new Error('HTTP ' + response.responseCode));
            return;
          }
          resolve();
        }).catch((err: Error) => {
          reject(err);
        });
      });
    } finally {
      if (file >= 0) {
        try {
          fs.closeSync(file);
        } catch (e) {
          // ignore
        }
      }
      httpRequest.off('dataReceive');
      httpRequest.off('dataReceiveError');
      httpRequest.destroy();
    }
  }

  async unzipTo(srcZip: string, outDir: string): Promise<void> {
    await zlib.decompressFile(srcZip, outDir);
  }

  async importLocal(): Promise<void> {
    this.bootState = 4;
    this.message = '';
    const documentPicker = new picker.DocumentViewPicker(getContext(this) as common.UIAbilityContext);
    let uris: string[] = [];
    try {
      const result = await documentPicker.select({ maxSelectNumber: 20 });
      uris = result;
    } catch (e) {
      this.bootState = 2;
      this.message = '未选择文件';
      return;
    }
    const cacheDir = this.sandboxFiles + '/cache';
    try {
      fs.mkdirSync(cacheDir, true);
    } catch (e) {
      // already exists
    }
    for (let i = 0; i < uris.length; i++) {
      const uri = uris[i];
      const name = uri.substring(uri.lastIndexOf('/') + 1);
      this.progressText = '正在导入 ' + name;
      this.progressValue = Math.floor((i + 1) * 100 / Math.max(1, uris.length));
      const tmp = cacheDir + '/' + name;
      try {
        fs.copyFileSync(uri, tmp);
        await this.unzipTo(tmp, this.baseDir);
        try {
          fs.unlinkSync(tmp);
        } catch (e) {
          // ignore
        }
      } catch (e) {
        this.bootState = 5;
        this.message = '导入失败：' + name + '（' + JSON.stringify(e) + '）';
        return;
      }
      await this.delay(50);
    }
    await this.finishData();
  }

  async finishData(): Promise<void> {
    try {
      await fs.access(this.dataDir + '/startup.tjs');
      this.bootState = 1;
      this.progressText = '游戏数据已就绪';
      this.progressValue = 100;
    } catch (e) {
      this.bootState = 5;
      this.message = '数据不完整：未找到 startup.tjs，请检查压缩包内容';
    }
  }

  delay(ms: number): Promise<void> {
    return new Promise<void>((resolve) => setTimeout(resolve, ms));
  }

  startGame(): void {
    entryNapi.setExternalDirs(this.baseDir, this.saveDir);
    this.bootState = 6;
  }

  build() {
    Column() {
      if (this.bootState === 0) {
        Text(this.progressText).fontSize(16).fontColor(Color.Gray)
      } else if (this.bootState === 1) {
        Column({ space: 16 }) {
          Text('缘之空：高清重制').fontSize(30).fontWeight(FontWeight.Bold).fontColor(Color.White)
          Text('游戏数据已就绪，点击开始').fontSize(14).fontColor(Color.Gray)
          Button('开始游戏').width(220).height(60).fontSize(18).onClick(() => {
            this.startGame();
          })
        }
      } else if (this.bootState === 6) {
        XComponent({
          id: 'gameview',
          type: XComponentType.SURFACE,
          libraryname: 'entry'
        })
          .onLoad((context) => {
            entryNapi.onLoad(context);
            entryNapi.startEngine();
          })
          .width('100%')
          .height('100%')
      } else if (this.bootState === 2) {
        Column({ space: 14 }) {
          Text('缘之空：高清重制').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Color.White)
          Text('需要游戏数据（约 3.6 GB）：可在线下载，或从本地 zip 压缩包导入').fontSize(13).fontColor(Color.Gray)
          TextInput({ text: this.baseUrl, placeholder: '下载地址（GitHub Release 页面）' })
            .width('86%')
            .onChange((value: string) => {
              this.baseUrl = value;
            })
          Button('下载游戏数据').width(220).height(52).onClick(() => {
            this.downloadAll();
          })
          Button('从本地压缩包导入').width(220).height(52).backgroundColor('#3A5BA0').onClick(() => {
            this.importLocal();
          })
          Text(this.message).fontSize(12).fontColor(Color.Red).maxLines(4)
        }
        .width('100%')
        .justifyContent(FlexAlign.Center)
      } else if (this.bootState === 3 || this.bootState === 4) {
        Column({ space: 14 }) {
          Progress({ value: this.progressValue, total: 100 }).width(260).color(Color.White)
          Text(this.progressText).fontSize(13).fontColor(Color.Gray)
        }
      } else {
        Column({ space: 14 }) {
          Text('出错了').fontSize(18).fontColor(Color.White)
          Text(this.message).fontSize(12).fontColor(Color.Red).maxLines(6)
          Button('返回').width(160).height(44).onClick(() => {
            this.prepare();
          })
        }
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
  }
};
