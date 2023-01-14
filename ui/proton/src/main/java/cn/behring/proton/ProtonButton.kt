package cn.behring.proton

import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable

@Composable
fun ProtonButton() {
    Button(onClick = { ProtonText().click() }) {
        Text(text = "ProtonButton")
    }
}