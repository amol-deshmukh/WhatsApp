package com.example.whatsapp.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.whatsapp.ui.chat.ChatRoute
import com.example.whatsapp.ui.chatlist.ChatListRoute
import com.example.whatsapp.ui.media.MediaViewerRoute

private object Routes {
    const val CHATS = "chats"
    const val CHAT = "chat/{conversationId}"
    const val MEDIA = "media/{messageId}"
    fun chat(id: String) = "chat/$id"
    fun media(id: String) = "media/$id"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = Routes.CHATS) {
        composable(Routes.CHATS) {
            ChatListRoute(onOpenChat = { navController.navigate(Routes.chat(it)) })
        }
        composable(Routes.CHAT, arguments = listOf(navArgument("conversationId") { type = NavType.StringType })) { entry ->
            ChatRoute(
                conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenMedia = { navController.navigate(Routes.media(it)) },
            )
        }
        composable(Routes.MEDIA, arguments = listOf(navArgument("messageId") { type = NavType.StringType })) { entry ->
            MediaViewerRoute(
                messageId = entry.arguments?.getString("messageId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
