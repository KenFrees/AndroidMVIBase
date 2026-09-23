package com.ttxz.base

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.ttxz.base.ui.theme.AndroidMVIBaseTheme
import dagger.hilt.android.AndroidEntryPoint

private const val HOME_ROUTE = "home"
private const val UI_COMPONENTS_ROUTE = "ui-components"

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
                        HomeScreen(onOpenComponents = {
                            navController.navigate(UI_COMPONENTS_ROUTE)
                        })
                    }
                    composable(UI_COMPONENTS_ROUTE) {
                        UiComponentsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpenComponents: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Hello AndroidMVIBase!")
        Button(onClick = onOpenComponents) {
            Text(text = stringResource(R.string.open_ui_components))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AndroidMVIBaseTheme {
        HomeScreen(onOpenComponents = {})
    }
}
