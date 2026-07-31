package com.evelorion.phone.telecom

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

object CallScreeningRole {
    fun isHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roles = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return false
        return roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roles = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }
}
