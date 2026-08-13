package com.hulk.pillsapp

import android.content.Context
import com.hulk.pillsapp.ledger.DebugLedgerSelfTest

fun runDebugSelfTests(context: Context) {
    Thread({ DebugLedgerSelfTest.run(context.applicationContext) }, "ledger-debug-self-test").start()
}
