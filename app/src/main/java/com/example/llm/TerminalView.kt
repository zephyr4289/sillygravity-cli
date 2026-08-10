package com.example.llm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalView(modifier: Modifier = Modifier) {
    val logs by TerminalLogger.logs.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to the latest line when logs update
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFF0D0D0D))
            .padding(8.dp)
    ) {
        items(logs) { logMessage ->
            Text(
                text = logMessage,
                color = Color(0xFF00FF66), // High-visibility terminal green
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
    }
}
