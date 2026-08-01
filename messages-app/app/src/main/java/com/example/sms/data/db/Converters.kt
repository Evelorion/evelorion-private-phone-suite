package com.example.sms.data.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun categoryToString(v: MsgCategory): String = v.name
    @TypeConverter fun stringToCategory(v: String): MsgCategory =
        runCatching { MsgCategory.valueOf(v) }.getOrDefault(MsgCategory.OTHER)

    @TypeConverter fun statusToString(v: MsgStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): MsgStatus =
        runCatching { MsgStatus.valueOf(v) }.getOrDefault(MsgStatus.RECEIVED)

    @TypeConverter fun typeToString(v: MsgType): String = v.name
    @TypeConverter fun stringToType(v: String): MsgType =
        runCatching { MsgType.valueOf(v) }.getOrDefault(MsgType.TEXT)
}
