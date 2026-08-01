package com.example.sms.util

import com.example.sms.data.db.MsgCategory

/** 按发件人与正文关键词把会话分到 个人 / 交易 / 推广 */
object Classifier {

    private val promoKeywords = listOf(
        "退订", "回T退订", "TD退订", "优惠", "特惠", "促销", "折扣", "限时", "秒杀",
        "领取", "红包", "活动", "上新", "会员日", "满减", "免费领", "点击", "抢购",
    )

    private val transactionKeywords = listOf(
        "验证码", "动态码", "校验码", "取件码", "提货码", "快递", "包裹", "驿站", "菜鸟",
        "订单", "支付", "余额", "入账", "支出", "转账", "消费", "还款", "账单", "发货",
        "签收", "物流", "银行", "尾号",
    )

    /** 常见的服务号号段：10/95/106 开头，或长度 <= 8 的短号 */
    private fun isServiceNumber(address: String): Boolean {
        val n = PhoneUtils.normalize(address)
        if (n.any { it.isLetter() }) return true
        return n.startsWith("106") || n.startsWith("95") || n.startsWith("10") || n.length <= 8
    }

    fun classify(address: String, body: String): MsgCategory {
        val text = body
        return when {
            promoKeywords.any { text.contains(it) } && isServiceNumber(address) -> MsgCategory.PROMO
            transactionKeywords.any { text.contains(it) } -> MsgCategory.TRANSACTION
            isServiceNumber(address) -> MsgCategory.OTHER
            else -> MsgCategory.PERSONAL
        }
    }

    /** 是否应作为骚扰信息拦截（仅在设置里开启拦截时使用） */
    fun isSpam(address: String, body: String): Boolean =
        classify(address, body) == MsgCategory.PROMO
}
