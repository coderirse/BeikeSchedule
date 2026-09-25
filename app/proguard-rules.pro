# 贝壳课表 R8 规则。
#
# 历史上 release 未声明 proguardFiles，R8 仅靠依赖库 consumer rules 运行且线上无恙
# （Compose/Room/kotlinx.serialization 均自带规则）。本文件显式化后保持最小集：
# 只在确认反射/序列化需要时逐条追加，并注明原因，不做大而全的兜底 keep。

# kotlinx.serialization：@Serializable 类的 serializer() 经生成的伴生对象查找，
# consumer rules 已覆盖大多数场景；显式 keep 住序列化器构造，防多态/泛型场景漏网。
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# WebView 注入脚本通过桥回传的是纯字符串，不涉及反射；如将来通过
# Class.forName 动态加载类，在此追加 keep 并注明来源。
