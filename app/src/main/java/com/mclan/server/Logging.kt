package com.mclan.server

/**
 * A thin wrapper so source files that also define/contain a class named *Log* never pick up
 * android.util.Log by accident.
 */
fun androidLogI(tag: String, message: String) {
    android.util.Log.i(tag, message)
}

fun androidLogW(tag: String, message: String?, t: Throwable? = null) {
    android.util.Log.w(tag, message, t)
}

fun androidLogE(tag: String, message: String?, t: Throwable? = null) {
    android.util.Log.e(tag, message, t)
}
