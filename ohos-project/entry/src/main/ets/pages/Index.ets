import entryNapi from 'libentry.so';
import { common } from '@kit.AbilityKit';
import { fileIo as fs, picker } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import zlib from '@ohos.zlib';
import { util } from '@kit.ArkTS';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { promptAction, window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';

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

// The app-owned public Download folder: Download/<bundleName>. On HarmonyOS
// NEXT this exact per-app folder is writable WITHOUT any permission
// (unlike arbitrary paths under Download), and shows under the app name in
// the file manager. The system creates it automatically.
const APP_FOLDER = 'com.lightwinder.yosuganosora.hdremake';
const DOWNLOAD_ROOT = '/storage/Users/currentUser/Download';

@Entry
@Component
struct Index {
  // 0 checking, 1 ready, 2 need data, 3 downloading, 4 importing, 5 error, 6 running
  @State bootState: number = 0;
  @State progressText: string = '';
  @State progressValue: number = 0;
  @State baseUrl: string = '';
  @State proxyUrl: string = '';
  @State message: string = '';
  @State needInit: boolean = false;
  @State uiEpoch: number = 0;
  private sandboxFiles: string = '';
  private baseDir: string = '';
  private saveDir: string = '';
  private appDir: string = '';
  private dataDir: string = '';
  private assets: DataAsset[] = [];
  private manifest: DataManifest | null = null;

  aboutToAppear(): void {
    this.sandboxFiles = (getContext(this) as common.UIAbilityContext).filesDir;
    // Show the bootstrap screen immediately with ZERO filesystem or
    // permission work, so no platform call can stall the UI thread before
    // the user sees it. Data checks run on demand below.
    this.bootState = 2;
    this.progressText = '需要下载或导入游戏数据';
  }

  onPageShow(): void {
    // Do not auto-check on page show; keep the bootstrap screen visible.
  }

  async prepare(): Promise<void> {
    if (this.bootState === 6) {
      return; // already running
    }
    this.bootState = 0;
    this.progressText = '正在检查游戏数据…';
    try {
      await this.prepareInternal();
    } catch (e) {
      // Never crash the app on the bootstrap path: show the error instead.
      this.bootState = 5;
      this.message = '初始化失败：' + JSON.stringify(e);
      this.log('prepare failed: ' + JSON.stringify(e));
    }
  }

  private log(msg: string): void {
    hilog.info(0x0000, 'YosugaOHOS', '%{public}s', msg);
  }

  prepareInternal(): void {
    this.log('prepareInternal begin');
    // NOTE: no permission dialog here. On HarmonyOS 7 (API 26) the system
    // permission dialog is a UIExtension whose close can wipe the host page
    // content (black screen). Permissions are requested on demand when the
    // user taps download/import instead.

    // Prefer the user-granted public Download/<bundleName> folder: after
    // initPublicDir() runs (DocumentViewPicker.save DOWNLOAD mode) the system
    // creates Download/<bundleName>/ and grants the app persistent access
    // without any media permission. Probe it; fall back to the sandbox.
    this.appDir = DOWNLOAD_ROOT + '/' + APP_FOLDER;
    let publicOk = false;
    try {
      fs.mkdirSync(this.appDir + '/data', true);
      fs.mkdirSync(this.appDir + '/savedata', true);
      fs.accessSync(this.appDir);
      publicOk = true;
    } catch (e) {
      this.log('public app dir not granted yet: ' + JSON.stringify(e));
    }
    if (publicOk) {
      this.baseDir = this.appDir;
      this.saveDir = this.appDir + '/savedata';
      this.dataDir = this.appDir + '/data';
      this.needInit = false;
      this.message = '';
      this.log('data dir: public ' + this.appDir);
    } else {
      this.baseDir = this.sandboxFiles;
      this.saveDir = this.sandboxFiles + '/savedata';
      this.dataDir = this.sandboxFiles + '/data';
      this.needInit = true;
      this.message = '未初始化下载目录——请点击「初始化下载目录」以便在系统下载目录存放游戏数据（便于连接电脑管理）';
      this.log('data dir: sandbox (public not granted)');
    }
    try {
      fs.mkdirSync(this.saveDir, true);
    } catch (e) {
      // already exists
    }
    try {
      fs.mkdirSync(this.dataDir, true);
    } catch (e) {
      // already exists
    }

    const ready = this.dataReady();
    this.log('dataReady=' + ready + ' dataDir=' + this.dataDir);
    if (ready) {
      this.startGame();
      return;
    }
    this.loadManifest();
    this.log('manifest loaded, assets=' + this.assets.length);
    this.bootState = 2;
    this.progressText = '需要下载或导入游戏数据';
    this.uiEpoch++;
    this.log('bootstrap page shown');
  }

  loadManifest(): void {
    try {
      // Synchronous: an async rawfile read that never settles would leave the
      // UI thread stuck on some system versions (black screen).
      const manager = (getContext(this) as common.UIAbilityContext).resourceManager;
      const bytes = manager.getRawFileContentSync('data-assets.json');
      const text = new util.TextDecoder('utf-8').decodeWithStream(bytes);
      const parsed = JSON.parse(text) as DataManifest;
      this.manifest = parsed;
      this.baseUrl = parsed.baseUrl;
      this.assets = parsed.assets;
    } catch (e) {
      this.message = '读取数据清单失败，请手动设置下载地址';
    }
  }

  /** Ask the user once to grant the app-owned Download/<bundleName> folder.
   *  documentViewPicker.save() with DOWNLOAD mode makes the system create
   *  Download/<bundleName>/ and grants persistent access without any media
   *  permission. Returns true when the public folder became usable. */
  async initPublicDir(): Promise<boolean> {
    this.message = '';
    try {
      const dp = new picker.DocumentViewPicker(getContext(this) as common.UIAbilityContext);
      const opts = new picker.DocumentSaveOptions();
      opts.pickerMode = picker.DocumentPickerMode.DOWNLOAD;
      opts.newFileNames = ['YosugaNoSoraHD.initialized'];
      await dp.save(opts);
      // The system created Download/<bundleName>/ and granted access.
      this.appDir = DOWNLOAD_ROOT + '/' + APP_FOLDER;
      try {
        fs.mkdirSync(this.appDir + '/data', true);
      } catch (e) {
        // may already exist / not file-writable yet
      }
      try {
        fs.mkdirSync(this.appDir + '/savedata', true);
      } catch (e) {
        // may already exist
      }
      this.baseDir = this.appDir;
      this.saveDir = this.appDir + '/savedata';
      this.dataDir = this.appDir + '/data';
      this.needInit = false;
      this.log('public dir initialised: ' + this.appDir);
      return true;
    } catch (e) {
      this.message = '初始化下载目录失败：' + JSON.stringify(e);
      this.log('initPublicDir failed: ' + JSON.stringify(e));
      return false;
    } finally {
      // The picker is a UIExtension; rebuild after it closes so the page is
      // not left blank.
      this.uiEpoch++;
    }
  }

  /** Use the app-owned public Download folder (Download/<bundleName>) if the
   *  platform allows it without permissions; otherwise keep the sandbox.
   *  Returns true when the public folder is usable. */
  async ensurePublic(): Promise<boolean> {
    try {
      fs.mkdirSync(this.appDir, true);
      fs.accessSync(this.appDir);
    } catch (e) {
      this.log('public app dir unusable: ' + JSON.stringify(e));
      return false;
    }
    if (this.baseDir !== this.appDir) {
      this.baseDir = this.appDir;
      this.saveDir = this.appDir + '/savedata';
      this.dataDir = this.appDir + '/data';
      try {
        fs.mkdirSync(this.saveDir, true);
      } catch (e) {
        // already exists
      }
      try {
        fs.mkdirSync(this.dataDir, true);
      } catch (e) {
        // already exists
      }
      this.message = '';
      this.log('switched to public dir: ' + this.baseDir);
    }
    return this.baseDir === this.appDir;
  }

  async downloadAll(): Promise<void> {
    if (this.assets.length === 0) {
      this.loadManifest();
    }
    if (this.assets.length === 0) {
      this.message = '下载清单为空，请检查网络或稍后重试';
      return;
    }
    this.bootState = 3;
    this.message = '';
    await this.ensurePublic();
    this.uiEpoch++; // force a rebuild after the permission dialog closes
    const cacheDir = this.sandboxFiles + '/cache';
    try {
      fs.mkdirSync(cacheDir, true);
    } catch (e) {
      // already exists
    }
    let doneBytes = 0;
    const totalBytes = this.assets.reduce((sum, asset) => sum + asset.size, 0);
    const startTime = Date.now();
    for (const asset of this.assets) {
      const originalUrl = this.baseUrl + '/' + asset.name;
      const proxy = this.proxyUrl.trim();
      const url = proxy !== '' ? proxy + originalUrl : originalUrl;
      this.progressText = '正在下载 ' + asset.name;
      const tmp = cacheDir + '/' + asset.name;
      try {
        await this.downloadFile(url, tmp, asset.size, (downloaded: number) => {
          const doneTotal = doneBytes + downloaded;
          const elapsed = Math.max(1, (Date.now() - startTime) / 1000);
          const speed = doneTotal / elapsed;
          const pct = Math.min(99, Math.floor(doneTotal * 100 / Math.max(1, totalBytes)));
          this.progressValue = pct;
          this.progressText = '正在下载 ' + asset.name + '  ' + pct + '%  ' +
            this.fmtSize(doneTotal) + ' / ' + this.fmtSize(totalBytes) +
            '  (' + this.fmtSpeed(speed) + ')';
          // Force a rebuild so the progress text is repainted even if the
          // state-driven refresh fails on this system version.
          this.uiEpoch++;
        });
        if (asset.sha256 !== '' && !(await this.verifySha256(tmp, asset.sha256))) {
          throw new Error('SHA-256 校验失败，请重试');
        }
      } catch (e) {
        this.bootState = 5;
        this.message = '下载失败：' + asset.name + '（' + JSON.stringify(e) + '）\n' +
          '若为网络问题，请返回引导页点击「gh-proxy」或「我的代理」切换加速后再下载';
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

  async downloadFile(url: string, dest: string, totalSize: number,
    onProgress?: (downloaded: number) => void): Promise<void> {
    // Range-chunked download with resume + retry. GitHub release downloads
    // commonly drop mid-transfer; each chunk is retried a few times and
    // written at its exact offset so a retry continues where it left off.
    const file = fs.openSync(dest, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE);
    const chunkSize = 8 * 1024 * 1024;
    let offset = 0;
    try {
      while (offset < totalSize) {
        const end = Math.min(offset + chunkSize - 1, totalSize - 1);
        let got = false;
        for (let attempt = 0; attempt < 4 && !got; attempt++) {
          if (attempt > 0) {
            await this.delay(2000);
          }
          const httpRequest = http.createHttp();
          try {
            const response: http.HttpResponse = await httpRequest.request(url, {
              method: http.RequestMethod.GET,
              header: { 'Range': 'bytes=' + offset + '-' + end },
              expectDataType: http.HttpDataType.ARRAY_BUFFER,
              connectTimeout: 60000,
              readTimeout: 120000
            });
            if (response.responseCode === 206) {
              const data = response.result as ArrayBuffer;
              const length = data.byteLength;
              if (length > 0) {
                fs.writeSync(file.fd, data, { offset: offset });
                offset += length;
                if (onProgress) {
                  onProgress(offset);
                }
                got = true;
              }
            } else if (response.responseCode === 200) {
              // Server ignored the Range: write the whole body from the start.
              const data = response.result as ArrayBuffer;
              fs.truncateSync(file.fd, 0);
              fs.writeSync(file.fd, data, { offset: 0 });
              offset = totalSize;
              got = true;
              if (onProgress) {
                onProgress(totalSize);
              }
            } else if (response.responseCode === 416) {
              offset = totalSize;
              got = true;
            } else if (attempt === 3) {
              throw new Error('HTTP ' + response.responseCode);
            }
          } catch (e) {
            if (attempt === 3) {
              if (e instanceof Error) {
                throw e;
              }
              throw new Error(String(e));
            }
          } finally {
            httpRequest.destroy();
          }
        }
        if (!got && offset < totalSize) {
          throw new Error('下载中断，无法继续');
        }
        await this.delay(50);
      }
    } finally {
      fs.closeSync(file);
    }
  }

  async unzipTo(srcZip: string, outDir: string): Promise<void> {
    await zlib.decompressFile(srcZip, outDir);
  }

  /** True when the data folder holds a complete data tree or a data.xp3. */
  dataReady(): boolean {
    // Fully synchronous: async probes that never settle on some system
    // versions would freeze the UI thread (black screen).
    // A complete tree has startup.tjs plus the system scripts; a bare
    // startup.tjs (e.g. leftover from an old mini test build) is not enough.
    try {
      const entries = fs.listFileSync(this.dataDir);
      this.log('dataDir entries: ' + JSON.stringify(entries).slice(0, 300));
      const hasStartup = entries.indexOf('startup.tjs') >= 0;
      const hasSystem = entries.indexOf('system') >= 0;
      if (hasStartup && hasSystem) {
        return true;
      }
    } catch (e) {
      this.log('dataDir list failed: ' + JSON.stringify(e));
    }
    try {
      fs.accessSync(this.dataDir + '/data.xp3');
      return true;
    } catch (e) {
      return false;
    }
  }

  async verifySha256(path: string, expected: string): Promise<boolean> {
    const md = cryptoFramework.createMd('SHA256');
    const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    const chunk = new ArrayBuffer(1024 * 1024);
    try {
      while (true) {
        const len = fs.readSync(file.fd, chunk);
        if (len <= 0) {
          break;
        }
        const view = new Uint8Array(chunk.slice(0, len));
        await md.update({ data: view });
      }
    } finally {
      fs.closeSync(file);
    }
    const digest = await md.digest();
    const bytes = digest.data;
    let hex = '';
    for (let i = 0; i < bytes.length; i++) {
      hex += bytes[i].toString(16).padStart(2, '0');
    }
    return hex.toUpperCase() === expected.toUpperCase();
  }

  async showNotice(title: string, msg: string): Promise<void> {
    await promptAction.showDialog({
      title: title,
      message: msg,
      buttons: [{ text: '知道了', color: '#FFFFFF' }]
    });
  }

  /** Copy from a document picker uri (file:// or datashare://) to a sandbox
   * path. Plain copyFileSync fails on picker uris with error 13900002. */
  copyFromUri(uri: string, dest: string): void {
    let src: fs.File | null = null;
    try {
      src = fs.openSync(uri, fs.OpenMode.READ_ONLY);
    } catch (e) {
      // Some systems hand out datashare:// uris that openSync rejects; the
      // copyFile helper understands more uri schemes.
      fs.copyFileSync(uri, dest);
      return;
    }
    const dst = fs.openSync(dest, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    const buf = new ArrayBuffer(1024 * 1024);
    try {
      while (true) {
        const n = fs.readSync(src.fd, buf);
        if (n <= 0) {
          break;
        }
        const chunk = buf.slice(0, n);
        fs.writeSync(dst.fd, chunk);
      }
    } finally {
      fs.closeSync(src);
      fs.closeSync(dst);
    }
  }

  fmtSize(bytes: number): string {
    if (bytes >= 1024 * 1024 * 1024) {
      return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    }
    if (bytes >= 1024 * 1024) {
      return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }
    return Math.round(bytes / 1024) + ' KB';
  }

  fmtSpeed(bytesPerSec: number): string {
    return this.fmtSize(bytesPerSec) + '/s';
  }

  async importLocal(): Promise<void> {
    this.bootState = 4;
    this.message = '';
    await this.ensurePublic();
    this.uiEpoch++; // force a rebuild after the permission dialog closes
    const documentPicker = new picker.DocumentViewPicker(getContext(this) as common.UIAbilityContext);
    let uris: string[] = [];
    try {
      const result = await documentPicker.select({ maxSelectNumber: 20 });
      uris = result;
    } catch (e) {
      // User cancelled the picker: rebuild the whole bootstrap page so the
      // picker (also a UIExtension) cannot leave it blank.
      this.message = '未选择文件';
      this.prepare();
      return;
    }
    if (uris.length === 0) {
      this.message = '未选择文件';
      this.prepare();
      return;
    }
    // Force a rebuild after the picker (UIExtension) closes so the page is
    // not left blank, then continue importing.
    this.uiEpoch++;
    this.bootState = 4;
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
      const lower = name.toLowerCase();
      try {
        if (lower.endsWith('.xp3')) {
          // A bare data.xp3 goes straight into the data folder.
          fs.mkdirSync(this.dataDir, true);
          this.copyFromUri(uri, this.dataDir + '/data.xp3');
        } else if (lower.endsWith('.zip')) {
          const tmp = cacheDir + '/' + name;
          this.copyFromUri(uri, tmp);
          await this.unzipTo(tmp, this.baseDir);
          // A zip may contain data.xp3 at its root (no data/ prefix);
          // move it into the data folder so readiness checks pass.
          try {
            fs.accessSync(this.baseDir + '/data.xp3');
            fs.renameSync(this.baseDir + '/data.xp3', this.dataDir + '/data.xp3');
          } catch (e) {
            // no root-level xp3
          }
          try {
            fs.unlinkSync(tmp);
          } catch (e) {
            // ignore
          }
        } else if (lower.endsWith('.7z') || lower.endsWith('.001') || lower.endsWith('.002')) {
          this.message = '暂不支持 7z 格式，请使用 zip 压缩包或 data.xp3';
          await this.showNotice('暂不支持 7z 格式', '请选择 zip 压缩包或 data.xp3 文件');
          continue;
        } else {
          this.message = '不支持的文件类型：' + name;
          await this.showNotice('不支持的文件类型', '请选择 zip 压缩包或 data.xp3 文件');
          continue;
        }
      } catch (e) {
        this.bootState = 2;
        const detail = JSON.stringify(e);
        this.message = '导入失败：' + name + '\n' + detail + '\nuri: ' + uri;
        await this.showNotice('导入失败', detail);
        return;
      }
      await this.delay(50);
    }
    await this.finishData();
    this.uiEpoch++;
  }

  finishData(): void {
    if (this.dataReady()) {
      // Data verified: jump straight into the game. Next launch skips this
      // screen because onPageShow finds the data ready.
      this.startGame();
      return;
    }
    this.bootState = 2;
    this.progressValue = 0;
    this.message = '压缩包内容不正确：未找到 data 文件夹或 data.xp3，请重新选择或下载';
    this.showNotice('数据不完整', '请检查压缩包是否包含 data 文件夹或 data.xp3 文件');
  }

  delay(ms: number): Promise<void> {
    return new Promise<void>((resolve) => setTimeout(resolve, ms));
  }

  startGame(): void {
    if (this.bootState === 6) {
      return; // already running
    }
    entryNapi.setExternalDirs(this.baseDir, this.saveDir);
    this.bootState = 6;
    // The game uses a fixed 1920x1080 coordinate system: switch to
    // landscape fullscreen now that the bootstrap page is left behind.
    try {
      const ctx = getContext(this) as common.UIAbilityContext;
      window.getLastWindow(ctx).then((mainWindow: window.Window) => {
        mainWindow.setPreferredOrientation(window.Orientation.LANDSCAPE);
        mainWindow.setWindowLayoutFullScreen(true);
      }).catch(() => {
        // best effort
      });
    } catch (e) {
      // best effort
    }
  }

  build() {
    Column() {
      if (this.bootState === 0) {
        Text(this.progressText).fontSize(16).fontColor(Color.Gray)
      } else if (this.bootState === 6) {
        XComponent({
          id: 'gameview',
          type: XComponentType.SURFACE,
          libraryname: 'entry'
        })
          .onLoad((context: object) => {
            try {
              const ok: boolean = entryNapi.onLoad(context) as boolean;
              if (ok) {
                entryNapi.startEngine();
              } else {
                this.log('onLoad failed to attach the XComponent');
              }
            } catch (e) {
              // never let an exception kill the app
              this.log('onLoad exception: ' + JSON.stringify(e));
            }
          })
          .width('100%')
          .height('100%')
      } else if (this.bootState === 2) {
        Scroll() {
          Column({ space: 12 }) {
          Text('').height(24)
          Text('缘之空：高清重制').fontSize(26).fontWeight(FontWeight.Bold).fontColor(Color.White)
          Text('需要游戏数据（约 3.6 GB）：可在线下载，或从本地选择 zip 压缩包 / data.xp3 导入').fontSize(13).fontColor(Color.Gray)
          TextInput({ text: this.baseUrl, placeholder: '下载地址（GitHub Release 页面）' })
            .width('86%')
            .fontSize(12)
            .fontColor(Color.White)
            .placeholderColor(Color.Gray)
            .caretColor(Color.White)
            .backgroundColor('#333333')
            .onChange((value: string) => {
              this.baseUrl = value;
            })
          TextInput({ text: this.proxyUrl, placeholder: '加速代理前缀（留空=直连）' })
            .width('86%')
            .fontSize(12)
            .fontColor(Color.White)
            .placeholderColor(Color.Gray)
            .caretColor(Color.White)
            .backgroundColor('#333333')
            .onChange((value: string) => {
              this.proxyUrl = value;
            })
          Row({ space: 8 }) {
            Button('直连').fontSize(12).height(32).backgroundColor('#666666').onClick(() => {
              this.proxyUrl = '';
            })
            Button('gh-proxy').fontSize(12).height(32).backgroundColor('#2E5A88').onClick(() => {
              this.proxyUrl = 'https://gh-proxy.cn/';
            })
            Button('我的代理').fontSize(12).height(32).backgroundColor('#2E5A88').onClick(() => {
              this.proxyUrl = 'https://proxy.craft-hello.top/proxy/';
            })
          }
          Text('留空=直连 GitHub；加速代理前缀会自动拼在原下载链接前').fontSize(11).fontColor(Color.Gray).maxLines(2)
          if (this.needInit) {
            Button('初始化下载目录（在系统下载目录建专属文件夹）')
              .width('88%').height(48).backgroundColor('#2E5A88').onClick(() => {
                this.initPublicDir();
              })
          }
          Button('下载游戏数据').width(220).height(52).onClick(() => {
            this.downloadAll();
          })
          Button('从本地压缩包导入').width(220).height(52).backgroundColor('#3A5BA0').onClick(() => {
            this.importLocal();
          })
          Button('重新检查').width(220).height(44).backgroundColor('#555555').onClick(() => {
            this.prepare();
          })
          Text(this.message).fontSize(12).fontColor(Color.Red).maxLines(4)
          }
        }
        .width('100%')
        .padding({ top: 20, bottom: 40 })
      } else if (this.bootState === 3 || this.bootState === 4) {
        Column({ space: 14 }) {
          // Progress component may not repaint on some system versions;
          // the percentage text is the reliable progress indicator.
          Text(this.progressText).fontSize(16).fontWeight(FontWeight.Medium)
            .fontColor(Color.White).maxLines(4)
            .backgroundColor('#333333').padding(12).borderRadius(8)
            .textAlign(TextAlign.Center)
          Progress({ value: this.progressValue, total: 100 }).width(260).color(Color.White)
        }
      } else {
        Column({ space: 14 }) {
          Text('需要授权').fontSize(18).fontColor(Color.White)
          Text(this.message).fontSize(12).fontColor(Color.Red).maxLines(6)
          Button('重新授权并检查').width(200).height(48).onClick(() => {
            this.prepare();
          })
        }
      }
    }
    .key('boot-' + this.bootState + '-' + this.uiEpoch)
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
  }
};
