# Room 实体经过反射，保留字段名
-keep class com.evelorion.contacts.sync.db.** { *; }
-keep class com.evelorion.contacts.sync.model.** { *; }

# argon2kt 和 sqlcipher 的 JNI 绑定
-keep class com.lambdapioneer.argon2kt.** { *; }
-keep class net.zetetic.database.** { *; }

# commons 的联系人模型走 Gson 序列化，字段名不能混淆
-keep class org.fossify.commons.models.contacts.** { *; }
