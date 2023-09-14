package com.apps.amplifty.ui.myvoice

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MyVoiceViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "This is My Voice registration page (Coming soon)"
    }
    val text: LiveData<String> = _text
}