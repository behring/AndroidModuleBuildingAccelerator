package cn.behring.home

import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import cn.behring.network.NetworkButton

@Composable
fun HomeButton() {
    Button(onClick = { }) {
        Text(text = "HomeButton")
    }
    NetworkButton()
}