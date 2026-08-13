# 跨平台内容与字体管线

## 内容清单

`data/` 是当前唯一内容源。`data/content-manifest.json` 是生成物，不手工编辑。

- Android：Gradle 的 `preBuild` 自动运行生成器。
- Windows、macOS、iOS 和其他 CMake 平台：构建 `krkrsdl2` 前自动运行生成器。
- 日常生成采用路径、大小和修改时间缓存，只重新计算变化文件的 SHA-256。
- 发布或 CI 可加 `--full` 做一次全量哈希校验。

手工检查命令：

```sh
python3 tools/generate_content_manifest.py \
  --root data \
  --output data/content-manifest.json \
  --config content-packs.json \
  --cache build/content-manifest-cache.json
```

通常只在新增资源类别、调整首包/分包策略时修改 `content-packs.json`。替换、增加或删除普通素材不需要修改它。

生成器会拒绝大小写冲突、非 NFC 文件名、Windows 非法文件名和符号链接，避免同一份内容在不同平台产生不同结果。

## 运行时字体

`data/system/FontManager.tjs` 统一负责 FreeType 字体注册和运行时切换。启动时会把随包的 `Xiaolai-Regular.ttf` 设为跨平台默认字体。

加载并切换另一份内容字体：

```tjs
FontManager.setFreeTypeFont("my-theme", "font/MyTheme.ttf");
```

让一个已经存在的普通 Layer 跟随后续字体切换：

```tjs
FontManager.registerLayer(myLayer);
// 销毁 Layer 前：
FontManager.unregisterLayer(myLayer);
```

已有六套对话 `.tft` 字体继续兼容原设置界面，但改成首次选择时只加载对应字体族的 5 个字号。它们是预渲染字体，不会因为切换 FreeType 默认字体而自动变成 TTF；需要 FreeType 的新界面应使用普通 Layer 或关闭 MessageArea 的预渲染字体模式。
