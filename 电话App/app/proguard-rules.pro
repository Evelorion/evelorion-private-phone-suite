# ── 系统按名字实例化的东西，绝对不能被混淆或裁掉 ────────────────
#
# InCallService 是系统通过 manifest 里的类名反射创建的。R8 看不到任何
# 代码引用它，会认为"没人用"直接删掉 —— 表现是设成默认电话应用之后
# 一个来电都收不到，而且没有任何报错。
-keep class com.evelorion.phone.telecom.** { *; }
-keep class com.evelorion.phone.PhoneApplication { *; }
-keep class com.evelorion.phone.MainActivity { *; }
-keep class com.evelorion.phone.ui.CrashActivity { *; }

# 崩溃记录要能读出有意义的堆栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room 的生成代码
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# SQLCipher 走 JNI，名字不能变
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# Argon2 也是 JNI
-keep class com.lambdapioneer.argon2kt.** { *; }

# OkHttp 的可选依赖
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
