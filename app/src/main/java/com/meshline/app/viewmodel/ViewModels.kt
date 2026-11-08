package com.meshline.app.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModels (UI -> ViewModels -> MessagingService -> MeshRouter ...).
 * Phase E wires these to the real service. Phase A keeps the shape:
 *  - ChatViewModel    : Nearby public feed (500 chars max, labelled "not encrypted")
 *  - PeersViewModel   : peers (nickname, signal, hops), block/mute
 *  - PrivateChatViewModel : encrypted threads with Queued/Sent/Delivered/Read
 *  - SettingsViewModel: nickname, power mode, relay on/off
 *  - MeshStatusViewModel  : peers, relayed count, hop distribution
 *
 * All expose StateFlow so Compose redraws on change; no Android classes leak
 * into the ViewModels beyond lifecycle types.
 */
class ChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages
}
