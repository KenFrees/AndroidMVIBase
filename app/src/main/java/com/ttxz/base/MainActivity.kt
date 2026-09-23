package com.ttxz.base

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ttxz.base.ui.loading.LoadingDialog
import com.ttxz.base.ui.theme.AndroidMVIBaseTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidMVIBaseTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "AndroidMVIBase",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    var isLoading by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current

    LaunchedEffect(isLoading) {
        if (isLoading) {
            delay(2_000)
            isLoading = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Hello $name!")
            Button(onClick = { isLoading = true }) {
                Text(text = stringResource(R.string.show_loading))
            }

            Button(
                modifier = Modifier.padding(top = 50.dp),
                onClick = {
                Toast.makeText(context, "点击了", Toast.LENGTH_SHORT).show()
            }) {
                Text(text = "点击试试")
            }
        }
    }

    LoadingDialog(
        visible = isLoading,
        message = stringResource(R.string.loading_message),
        dismissOnBackPress = false,
        dismissOnClickOutside = true,
        onDismissRequest = {
            isLoading = false
        }
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AndroidMVIBaseTheme {
        Greeting("Android")
    }
}
