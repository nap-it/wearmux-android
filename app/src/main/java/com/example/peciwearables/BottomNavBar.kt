package com.example.peciwearables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable

@Composable
fun CustomBottomBar(
    selectedTab: Tab,
    onItemSelected: (Tab) -> Unit,
    items: List<NavItem> = navItems,
) {
    NavigationBar(
        containerColor = Constants.cardBackground,
        tonalElevation = 0.dp
    ) {
        items.forEach { item ->
            val selected = selectedTab == item.tab

            Box(
                modifier = Modifier
                    .weight(if (selected) 2f else 1f)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) Constants.cardBackgroundElevated else Color.Transparent)
                        .padding(if (selected) 6.dp else 0.dp)
                        .then(
                            if (!selected) Modifier.padding(6.dp) else Modifier
                        )
                        .clickable { onItemSelected(item.tab) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (selected) Constants.accentColor else Constants.cardBackgroundElevated)
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = if (selected) Color.Black else Constants.secondaryTextColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        AnimatedVisibility(
                            visible = selected,
                            enter = expandHorizontally() + fadeIn(),
                            exit = shrinkHorizontally() + fadeOut()
                        ) {
                            Text(
                                text = item.label,
                                color = Constants.primaryTextColor,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}