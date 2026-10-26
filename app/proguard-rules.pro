# 知述目前未开启 R8 混淆（app/build.gradle.kts 里 isMinifyEnabled=false），
# 本文件是 release 配置的引用兜底：文件不存在时，将来一开混淆就会直接构建失败。
# 开混淆时需要在这里补的典型 keep 规则：
#   - kotlinx.serialization 的 DTO（@Serializable 类，R8 需 serializer 保留）
#   - Room 实体与 DAO（通常由 room 插件的规则自动带上）
#   - pdfbox / ktor 等依赖库自带的 consumer 规则之外的反射点
