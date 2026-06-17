package com.jon2g.aa_keyboard_unlock

/** Cross-process signals: gearhead → Maps for projected car IME bind/show. */
object KeyboardBridge {
  /** Maps should run rel.d → snp.j/k before gearhead opens xcu/xdb. */
  const val ACTION_PREPARE_MAPS_IME = "com.jon2g.aa_keyboard_unlock.action.PREPARE_MAPS_IME"

  /** Fallback: open keyboard from Maps when gearhead has no cached IME. */
  const val ACTION_OPEN_MAPS_KEYBOARD = "com.jon2g.aa_keyboard_unlock.action.OPEN_MAPS_KEYBOARD"

  /** Maps header mic (gmm_mic) — gearhead should allow kxe.F(10) for this window. */
  const val ACTION_MAPS_MIC_VOICE = "com.jon2g.aa_keyboard_unlock.action.MAPS_MIC_VOICE"

  /** Custom overlay submitted a search query — Maps process runs snp.b / rek.e. */
  const val ACTION_SUBMIT_MAPS_SEARCH = "com.jon2g.aa_keyboard_unlock.action.SUBMIT_MAPS_SEARCH"

  /** Dismiss projected keyboard overlay (navigation / AA layout change). */
  const val ACTION_CLOSE_MAPS_KEYBOARD = "com.jon2g.aa_keyboard_unlock.action.CLOSE_MAPS_KEYBOARD"

  const val EXTRA_QUERY = "query"
}
