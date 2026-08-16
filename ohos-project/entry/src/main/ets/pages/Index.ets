import entryNapi from 'libentry.so';

@Entry
@Component
struct Index {
  build() {
    Column() {
      XComponent({
        id: 'gameview',
        type: XComponentType.SURFACE,
        libraryname: 'entry'
      })
        .onLoad((context) => {
          // The onLoad context carries the OH_NativeXComponent handle. Hand it
          // to the native side so the SDL video driver can obtain the native
          // window, then start the Kirikiri engine thread.
          entryNapi.onLoad(context);
          entryNapi.startEngine();
        })
        .width('100%')
        .height('100%')
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
  }
}
