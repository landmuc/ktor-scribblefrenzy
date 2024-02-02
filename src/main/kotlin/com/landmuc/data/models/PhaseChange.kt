package com.landmuc.data.models

import com.landmuc.data.Room
import com.landmuc.util.Constants.TYPE_PHASE_CHANGE

data class PhaseChange(
    var phase: Room.Phase?,
    var time: Long,
    val drawingPlayer: String? = null
): BaseModel(TYPE_PHASE_CHANGE)
