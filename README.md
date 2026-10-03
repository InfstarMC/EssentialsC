# EssentialsC

轻量、现代、面向 Paper 服务端的基础功能插件，灵感来自 CMI，但更聚焦于常用能力与模块化构建。

[![Version](https://img.shields.io/github/v/release/InfstarMC/EssentialsC?style=flat-square)](https://github.com/InfstarMC/EssentialsC/releases)
[![License](https://img.shields.io/github/license/InfstarMC/EssentialsC?style=flat-square)](LICENSE)
[![Paper](https://img.shields.io/badge/Paper-1.21.11%2B-blue?style=flat-square)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square)](https://adoptium.net/)

## 项目定位

- 最低支持版本为 `Paper 1.21.11`
- 已适配 `Paper 26.3`
- 插件统一使用 Paper 公共 API 和 Java 21 字节码，无需按服务端版本拆分构建
- 配置按职责拆分：主行为与 SkinBridge、模块开关、菜单布局和语言文本分别管理
- 支持运行期模块开关，避免为不同功能组合构建多个插件版本

## 主要功能

### 便捷方块

- `/workbench` `(/wb)`
- `/anvil`
- `/cartographytable` `(/ct, /cartography)`
- `/grindstone` `(/gs)`
- `/loom`
- `/smithingtable` `(/st, /smithing)`
- `/stonecutter` `(/sc)`
- `/enderchest` `(/ec)`
- `/essc blocks` 打开便捷菜单

### 玩家功能

- `/fly`
- `/nightvision` `(/nv)`
- `/glow`
- `/heal`
- `/feed`
- `/repair` `(/rep)`
- `/hat`
- `/suicide` `(/die)`
- `/vanish` `(/v)`
- `/seen` `(/info)` 管理员玩家详细信息查询
- `/tpsbar`
- `/essc admin` 管理模式切换

### 控制台命令

- `/essc help`、`/essc version`、`/essc reload`
- `/seen [玩家]`、`/heal [玩家]`、`/feed [玩家]`、`/tpsbar [玩家]`
- `/essc skin [status|refresh] [玩家]`

依赖发送者位置、背包或玩家交互状态的命令仅允许在游戏内执行。控制台执行玩家状态命令时必须明确提供目标玩家。

### 其它功能

- Shift + 右键快捷打开潜影盒
- 潜影盒交互保护，尽量避免刷物品、吞物品和嵌套放入问题
- 管理模式独立背包、装备栏与状态切换
- Enderman 掉落方块控制

## 模块配置

项目默认构建一个完整插件。玩家常用命令、Vanish 与 TPA 属于核心功能，始终加载并通过权限控制；其他可选功能由 `plugins/EssentialsC/modules.yml` 控制。

| 模块 | 默认状态 | 说明 |
| --- | --- | --- |
| `blocks` | 开启 | 便捷方块命令、`/essc blocks` 菜单、潜影盒快捷打开 |
| `admin-mode` | 开启 | `/essc admin` 管理模式与独立状态保存 |
| `tpsbar` | 开启 | 插件版 TPSBar，检测到服务端原生命令时自动避免冲突 |
| `mob-drops` | 关闭 | 末影人掉落控制，默认关闭以保留过去标准版行为 |
| `skin-bridge` | 关闭 | 查询外置 Yggdrasil profile，并通过 MineSkin 与 Paper Profile API 同步皮肤 |

修改模块开关后使用 `/essc reload` 即可刷新运行期服务、监听器和命令门禁。可选模块命令始终注册，模块关闭时会返回统一的停用提示，因此无需为了启用命令重启服务器。

## 安装说明

1. 从 [Releases](https://github.com/InfstarMC/EssentialsC/releases) 下载所需版本。
2. 将插件放入服务端的 `plugins/` 目录。
3. 启动一次服务端以生成配置文件。
4. 按需修改 `plugins/EssentialsC/` 下的配置文件与 `lang/` 语言文件。
5. 使用权限插件为玩家分配所需权限。

维护功能建议独立安装 [Maintenance](https://github.com/kennytv/Maintenance)。EssentialsC 不再内置维护模式，也不要求安装该插件。

## 配置说明

当前配置按功能职责拆分，避免所有设置堆积在一个文件中：

SkinBridge 默认关闭。使用前需启用 `modules.yml` 中的 `modules.skin-bridge.enabled`，填写 `config.yml` 中的 `skin-bridge.mineskin.api-key`，并至少启用一个 Provider。真实密钥只应填写在服务器运行目录的 `config.yml` 中，不要写入源码或提交到公开仓库。该模块不要求安装 SkinsRestorer。默认支持 InfstarMC 与自定义 Blessing Skin Provider；Provider 的 `name` 可自由修改，并会显示在日志和 `/essc skin status <玩家>` 中。生成后的 MineSkin 纹理会按皮肤 URL 缓存到 `skin-cache.yml`，并遵守配置的 TTL。升级到配置版本 3 时，旧的 LittleSkin Provider 配置会被自动移除。

- `config.yml`
  - 语言选择
  - 管理模式行为
  - 掉落控制
  - TPSBar 模式
  - TPA 请求、预热、冷却和音效
  - SkinBridge、MineSkin、Provider 与排除名单
- `modules.yml`
  - 功能模块开关
- `blocks-menu.yml`
  - 便捷菜单分区、槽位、材质和权限
- `lang/zh_CN.yml`、`lang/en_US.yml`
  - 命令反馈
  - 帮助信息
  - 菜单文本
  - 管理模式文本
  - TPSBar 文本

主配置当前使用 `config-version: 3`。SkinBridge 直接配置在 `config.yml` 中，便捷菜单配置仍保存在独立文件中。

版本变化与升级说明见 [CHANGELOG.md](CHANGELOG.md)。

## 权限示例

常用权限节点：

```text
essentialsc.command.blocks
essentialsc.command.workbench
essentialsc.command.enderchest
essentialsc.command.fly
essentialsc.command.nightvision
essentialsc.command.glow
essentialsc.command.heal
essentialsc.command.feed
essentialsc.command.repair
essentialsc.command.vanish
essentialsc.vanish.see
essentialsc.command.seen
essentialsc.command.tpa
essentialsc.command.tpahere
essentialsc.command.tpaall
essentialsc.command.tpaccept
essentialsc.command.tpdeny
essentialsc.command.tpignore
essentialsc.tpa.bypass-warmup
essentialsc.tpa.bypass-cooldown
# 使用数字覆盖玩家预热时间，例如 3 秒
essentialsc.tpa.warmup.3
essentialsc.command.admin
essentialsc.command.tpsbar
essentialsc.command.skin
essentialsc.command.skin.status
essentialsc.command.skin.refresh
essentialsc.command.skin.others
essentialsc.shulkerbox.open
essentialsc.mobdrops.enderman
essentialsc.*
```

具体默认值与完整节点以 `paper-plugin.yml` 为准。

## 从源码构建

```bash
git clone https://github.com/InfstarMC/EssentialsC.git
cd EssentialsC
./gradlew build
```

Windows 可使用：

```powershell
.\gradlew.bat build
```

构建产物输出到 `build/libs/EssentialsC-<version>.jar`。

常用任务：

```bash
./gradlew shadowJar
./gradlew build
./gradlew deployToPaper12111
./gradlew deployToPaper262
./gradlew deployToPaper263
./gradlew deployToLeaves262
```

## 本地测试服

本地测试服目录按需创建，当前已准备 Paper 26.3 测试服：

| 测试服 | 端口 | 部署任务 | 启动脚本 |
| --- | --- | --- | --- |
| Paper 1.21.11 | `25566` | `deployToPaper12111` | `test-server/paper-1.21.11/start.bat` |
| Paper 26.2 | `25567` | `deployToPaper262` | `test-server/paper-26.2/start.bat` |
| Paper 26.3 | `25569` | `deployToPaper263` | `test-server/paper-26.3/start.bat` |
| Leaves 26.2 | `25568` | `deployToLeaves262` | `test-server/leaves-26.2/start.bat` |

Paper 26.3 需要 Java 25。启动脚本默认使用系统 `java`，也可以设置 `JAVA_HOME_25` 指向 Java 25 安装目录。

IDEA 运行配置会先构建插件，再由启动脚本复制最新 JAR，并保留插件数据以便测试升级流程。手动执行部署任务会替换 `EssentialsC*.jar` 并删除 `plugins/EssentialsC` 数据目录，用于测试全新安装及默认配置生成。

## 开发说明

- 使用 `paperweight-userdev` 进行 Paper 开发
- 使用 Paper Lifecycle Command API 注册命令，避免直接反射 Bukkit CommandMap
- 运行时通过 `modules.yml` 控制模块加载，命令始终注册并按模块状态执行门禁
- 普通 push/PR 会执行构建与测试，版本标签继续生成 GitHub Release

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

## 仓库

- GitHub: <https://github.com/InfstarMC/EssentialsC>
- Gitea: <https://git.infstar.cn/InfStarMC/EssentialsC>
