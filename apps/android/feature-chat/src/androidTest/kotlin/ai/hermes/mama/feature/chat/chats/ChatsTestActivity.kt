package ai.hermes.mama.feature.chat.chats

import androidx.activity.ComponentActivity

/**
 * Activity mínima que hospeda la pantalla Chats en los tests instrumentados
 * (C3): el contenido lo fija el propio test vía `composeRule.setContent`.
 */
class ChatsTestActivity : ComponentActivity()
