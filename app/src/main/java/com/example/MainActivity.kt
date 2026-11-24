package com.example

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.OfficeFileType
import com.example.ui.doc.DocEditorScreen
import com.example.ui.doc.DocEditorViewModel
import com.example.ui.home.HomeScreen
import com.example.ui.home.HomeViewModel
import com.example.ui.pdf.PdfViewerScreen
import com.example.ui.pdf.PdfViewerViewModel
import com.example.ui.sheet.SheetEditorScreen
import com.example.ui.sheet.SheetEditorViewModel
import com.example.ui.theme.LiteOfficeTheme

const val ROUTE_HOME = "home"
const val ROUTE_DOC = "doc/{fileId}"
const val ROUTE_SHEET = "sheet/{fileId}"
const val ROUTE_PDF = "pdf/{fileId}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val homeViewModel: HomeViewModel = viewModel()
            val isDarkTheme by homeViewModel.isDarkTheme.collectAsStateWithLifecycle()

            LaunchedEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                )
            }

            LiteOfficeTheme(darkTheme = isDarkTheme) {
                val navController = rememberNavController()
                val app = LocalContext.current.applicationContext as Application

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_HOME,
                    enterTransition = { fadeIn(animationSpec = tween(140)) },
                    exitTransition = { fadeOut(animationSpec = tween(120)) },
                    popEnterTransition = { fadeIn(animationSpec = tween(140)) },
                    popExitTransition = { fadeOut(animationSpec = tween(120)) }
                ) {
                    composable(ROUTE_HOME) {
                        HomeScreen(
                            viewModel = homeViewModel,
                            onOpenFile = { file ->
                                when (file.fileType) {
                                    OfficeFileType.DOC -> navController.navigate("doc/${file.id}")
                                    OfficeFileType.SHEET -> navController.navigate("sheet/${file.id}")
                                    OfficeFileType.PDF -> navController.navigate("pdf/${file.id}")
                                }
                            }
                        )
                    }

                    composable(
                        route = ROUTE_DOC,
                        arguments = listOf(navArgument("fileId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val fileId = backStackEntry.arguments?.getString("fileId").orEmpty()
                        val vm: DocEditorViewModel = viewModel(
                            factory = DocEditorViewModel.Factory(app, fileId)
                        )
                        DocEditorScreen(
                            viewModel = vm,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        route = ROUTE_SHEET,
                        arguments = listOf(navArgument("fileId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val fileId = backStackEntry.arguments?.getString("fileId").orEmpty()
                        val vm: SheetEditorViewModel = viewModel(
                            factory = SheetEditorViewModel.Factory(app, fileId)
                        )
                        SheetEditorScreen(
                            viewModel = vm,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        route = ROUTE_PDF,
                        arguments = listOf(navArgument("fileId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val fileId = backStackEntry.arguments?.getString("fileId").orEmpty()
                        val vm: PdfViewerViewModel = viewModel(
                            factory = PdfViewerViewModel.Factory(app, fileId)
                        )
                        PdfViewerScreen(
                            viewModel = vm,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
