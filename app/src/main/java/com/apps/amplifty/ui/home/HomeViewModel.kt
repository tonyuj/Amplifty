package com.apps.amplifty.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "This is Amplify page (Mic and transcript coming soon)"
    }
    val text: LiveData<String> = _text
}