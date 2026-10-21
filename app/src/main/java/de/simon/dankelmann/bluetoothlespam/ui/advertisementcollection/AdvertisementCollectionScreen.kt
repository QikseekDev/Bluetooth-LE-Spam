package de.simon.dankelmann.bluetoothlespam.ui.advertisementcollection

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.simon.dankelmann.bluetoothlespam.ui.theme.FloatingNavBarClearance
import de.simon.dankelmann.bluetoothlespam.ui.theme.SpecterTopAppBarClearance
import de.simon.dankelmann.bluetoothlespam.Database.BuiltInCollectionDefinition
import de.simon.dankelmann.bluetoothlespam.Database.builtInCollectionDefinitions
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementSetType
import de.simon.dankelmann.bluetoothlespam.Enums.stringResId
import de.simon.dankelmann.bluetoothlespam.R

/** One selectable attack profile card on the Advertisement Collection screen. */
data class AdvertisementCollectionItem(
    val title: String,
    val targetLabel: String,
    val distanceLabel: String,
    val iconRes: Int,
    val onClick: () -> Unit,
)

@Composable
fun AdvertisementCollectionScreen(
    premadeItems: List<AdvertisementCollectionItem>,
    customItems: List<AdvertisementCollectionItem>,
    onAddClicked: () -> Unit,
    onMultiModeClicked: () -> Unit,
) {
    var customExpanded by remember { mutableStateOf(true) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClicked,
                modifier = Modifier.padding(bottom = FloatingNavBarClearance),
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Create custom group")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        // Both MainActivity's title bar and the floating nav pill blur on top of content rather
        // than reserving space -- Scaffold's own default window-inset padding would stack an
        // extra gap on top of the explicit SpecterTopAppBarClearance/FloatingNavBarClearance
        // this screen's own contentPadding already adds below.
        contentWindowInsets = WindowInsets(0.dp),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // Top/bottom padding include SpecterTopAppBarClearance/FloatingNavBarClearance so
            // the first/last card scrolls clear of the floating title bar and nav pill instead
            // of staying stuck underneath either one.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp + SpecterTopAppBarClearance,
                bottom = 16.dp + FloatingNavBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AdvertisementCollectionCard(
                    AdvertisementCollectionItem(
                        title = "Multi Mode",
                        targetLabel = "Combine Android, Apple/iOS, Windows and other lists",
                        distanceLabel = "Select multiple device lists",
                        iconRes = R.drawable.shuffle,
                        onClick = onMultiModeClicked,
                    ),
                )
            }
            items(premadeItems) { item -> AdvertisementCollectionCard(item) }

            if (customItems.isNotEmpty()) {
                item {
                    CategoryHeaderRow(
                        title = "Custom Groups",
                        expanded = customExpanded,
                        onToggle = { customExpanded = !customExpanded },
                    )
                }
                if (customExpanded) {
                    items(customItems) { item -> AdvertisementCollectionCard(item) }
                }
            }
        }
    }

}

@Composable
fun MultiModeSetupScreen(
    onBack: () -> Unit,
    onStart: (List<AdvertisementSetType>) -> Unit,
) {
    var showTutorial by remember { mutableStateOf(true) }
    MultiModePicker(onBack = onBack, onStart = onStart)
    if (showTutorial) {
        AlertDialog(
            onDismissRequest = { showTutorial = false },
            title = { Text("Multi Mode") },
            text = { Text("Choose lists from different databases—for example Android Fast Pair and Apple/iOS Continuity. Multi Mode advertises one item from each selected list at the same time.") },
            confirmButton = {
                Button(onClick = { showTutorial = false }) { Text("Choose Lists") }
            },
            dismissButton = {
                TextButton(onClick = { showTutorial = false }) { Text("Skip") }
            },
        )
    }
}

@Composable
private fun MultiModePicker(
    onBack: () -> Unit,
    onStart: (List<AdvertisementSetType>) -> Unit,
) {
    val context = LocalContext.current
    val selectedTypes = remember { mutableStateMapOf<AdvertisementSetType, Boolean>() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SpecterTopAppBarClearance),
    ) {
        Text("Choose device lists", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Select one or more lists. Lists from different databases can run together.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        builtInCollectionDefinitions.forEach { definition ->
            MultiModeDatabaseSection(definition, selectedTypes, context)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(
                onClick = { onStart(selectedTypes.filterValues { it }.keys.toList()) },
                enabled = selectedTypes.any { it.value },
            ) { Text("Start Multi Mode") }
        }
    }
}

@Composable
private fun MultiModeDatabaseSection(
    definition: BuiltInCollectionDefinition,
    selectedTypes: MutableMap<AdvertisementSetType, Boolean>,
    context: android.content.Context,
) {
    var expanded by remember { mutableStateOf(true) }
    Column(modifier = Modifier.fillMaxWidth()) {
        CategoryHeaderRow(
            title = definition.title.removeSuffix(" Collection"),
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            definition.types.distinct().forEach { type ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedTypes[type] = selectedTypes[type] != true
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selectedTypes[type] == true,
                        onCheckedChange = { selectedTypes[type] = it },
                    )
                    Text("${context.getString(type.stringResId())} List")
                }
            }
        }
    }
}

@Composable
private fun CategoryHeaderRow(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            modifier = Modifier.rotate(rotation),
        )
    }
}

@Composable
private fun AdvertisementCollectionCard(item: AdvertisementCollectionItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, style = MaterialTheme.typography.titleMedium)
                Text(text = item.targetLabel, style = MaterialTheme.typography.bodyMedium)
                Text(text = item.distanceLabel, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}
