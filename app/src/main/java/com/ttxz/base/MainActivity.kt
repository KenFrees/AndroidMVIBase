package com.ttxz.base

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ttxz.base.demo.ui.UiComponentsScreen
import com.ttxz.base.demo.post.PostDemoScreen
import com.ttxz.base.ui.theme.AndroidMVIBaseTheme
import dagger.hilt.android.AndroidEntryPoint
import com.ttxz.base.demo.logging.LogDemoScreen
import com.ttxz.base.demo.image.ImageDemoScreen

private const val HOME_ROUTE = "home"
private const val UI_COMPONENTS_ROUTE = "ui-components"
private const val POST_DEMO_ROUTE = "post-demo"
private const val LOG_DEMO_ROUTE = "log-demo"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidMVIBaseTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = HOME_ROUTE) {
                    composable(HOME_ROUTE) {
                        HomeScreen(
                            onOpenComponents = { navController.navigate(UI_COMPONENTS_ROUTE) },
                            onOpenNetworkDemo = { navController.navigate(POST_DEMO_ROUTE) },
                            onOpenLogDemo = { navController.navigate(LOG_DEMO_ROUTE) },
                            onOpenImageDemo = { navController.navigate("image-demo") },
                        )
                    }
                    composable(UI_COMPONENTS_ROUTE) {
                        UiComponentsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("image-demo") {
                        ImageDemoScreen(onBack = { navController.popBackStack() })
                    }
                    composable(LOG_DEMO_ROUTE) {
                        LogDemoScreen(onBack = { navController.popBackStack() })
                    }
                    composable(POST_DEMO_ROUTE) {
                        PostDemoScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    onOpenComponents: () -> Unit,
    onOpenNetworkDemo: () -> Unit,
    onOpenLogDemo: () -> Unit,
    onOpenImageDemo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Hello AndroidMVIBase!")
        Button(onClick = onOpenImageDemo) { Text(stringResource(R.string.open_image_demo)) }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp),
            onClick = onOpenComponents
        ) {
            Text(text = stringResource(R.string.open_ui_components))
        }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp),
            onClick = onOpenNetworkDemo
        ) {
            Text(text = stringResource(R.string.open_network_demo))
        }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp),
            onClick = onOpenLogDemo
        ) {
            Text(text = stringResource(R.string.open_log_demo))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AndroidMVIBaseTheme {
        HomeScreen(onOpenComponents = {}, onOpenNetworkDemo = {}, onOpenLogDemo = {}, onOpenImageDemo = {})
    }
}
