# 第三方说明

本项目自己的源码和文档采用 MIT 许可证，见 [LICENSE](LICENSE)。第三方组件与商标仍受各自条款约束。

| 组件 | 用途 | 项目与许可证 |
| --- | --- | --- |
| SPW Workshop API / Gradle 插件 0.1.0-dev22 | 编译接口和 `.spmod` 打包 | [Moriafly/spw-workshop-api](https://github.com/Moriafly/spw-workshop-api)，Apache-2.0 |
| Gradle Wrapper / Gradle 9.8.0 | 构建引导与构建工具 | [Gradle](https://github.com/gradle/gradle)，Apache-2.0 |
| Kotlin 2.4.20 | 编译器、标准库接口及测试 | [JetBrains/kotlin](https://github.com/JetBrains/kotlin)，Apache-2.0 |
| kotlinx.coroutines 1.10.2 | 协程接口及测试 | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)，Apache-2.0 |
| PF4J 3.12.0 | 插件入口接口 | [pf4j/pf4j](https://github.com/pf4j/pf4j)，Apache-2.0 |

仓库随附 Gradle Wrapper 的脚本和 JAR，版权信息保留在原始文件中；附带 [Gradle 许可证全文](licenses/Gradle-LICENSE.txt)，来源为 [Gradle v9.8.0 LICENSE](https://github.com/gradle/gradle/blob/v9.8.0/LICENSE)。其余组件由构建工具解析或由宿主提供，未将其运行库打入插件。

Salt Player / SPW 为其所属方的产品或商标。本项目是第三方插件，与官方没有隶属关系，不包含宿主程序、宿主反编译文件或音乐资源。集成测试需要用户自己安装相应版本的正版宿主。
