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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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

/** In-memory only: retains Multi Mode choices while the app process is alive. */
private object MultiModeListSelection {
    var selectedTypes: Set<AdvertisementSetType> = emptySet()
    var startSpacingMillis: Int = 0
}

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
    onStart: (List<AdvertisementSetType>, Long) -> Unit,
) {
    val context = LocalContext.current
    val tutorialPreferences = remember {
        context.getSharedPreferences("multi_mode_tutorial", android.content.Context.MODE_PRIVATE)
    }
    var showTutorial by remember {
        mutableStateOf(!tutorialPreferences.getBoolean("explained_v1", false))
    }
    fun dismissTutorial() {
        tutorialPreferences.edit().putBoolean("explained_v1", true).apply()
        showTutorial = false
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        MultiModePicker(onBack = onBack, onStart = onStart)
    }
    if (showTutorial) {
        AlertDialog(
            onDismissRequest = ::dismissTutorial,
            title = { Text("Multi Mode") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Advertise across platforms at the same time. For example, run an Android Fast Pair list alongside an Apple/iOS Continuity list.")
                    Text("How it works: expand a database and check the specific device lists you want. Multi Mode runs one item from every checked list concurrently; each list cycles through its items independently.")
                    Text("Tap Start Multi Mode when ready. The number of lists that can actually advertise simultaneously depends on your phone’s Bluetooth hardware. Stop advertising with the pause button.")
                    Text("Multi Mode can use more Bluetooth and battery power than a single list, so your battery may drain faster while it is running.")
                }
            },
            confirmButton = {
                Button(onClick = ::dismissTutorial) { Text("Choose Lists") }
            },
        )
    }
}

@Composable
private fun MultiModePicker(
    onBack: () -> Unit,
    onStart: (List<AdvertisementSetType>, Long) -> Unit,
) {
    val context = LocalContext.current
    val selectedTypes = remember {
        mutableStateMapOf<AdvertisementSetType, Boolean>().apply {
            MultiModeListSelection.selectedTypes.forEach { put(it, true) }
        }
    }
    var startSpacingMillis by remember { mutableStateOf(MultiModeListSelection.startSpacingMillis) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = SpecterTopAppBarClearance),
    ) {
        Text("Choose device lists", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Select one or more lists. Lists from different databases can run together.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Text(
            "Start spacing: ${if (startSpacingMillis == 0) "No delay" else "$startSpacingMillis ms"}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Delay between starting each selected list. Lower values bring lists online faster; higher values help slower Bluetooth hardware keep up.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Slider(
            value = startSpacingMillis.toFloat(),
            onValueChange = {
                startSpacingMillis = it.toInt()
                MultiModeListSelection.startSpacingMillis = startSpacingMillis
            },
            valueRange = 0f..500f,
            steps = 19,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
        )
        builtInCollectionDefinitions
            .filterNot { it.title == "Kitchen Sink Collection" }
            .forEach { definition ->
            MultiModeDatabaseSection(definition, selectedTypes, context)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(
                onClick = {
                    onStart(selectedTypes.filterValues { it }.keys.toList(), startSpacingMillis.toLong())
                },
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
                        val selected = selectedTypes[type] != true
                        selectedTypes[type] = selected
                        MultiModeListSelection.selectedTypes = selectedTypes
                            .filterValues { it }
                            .keys
                            .toSet()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selectedTypes[type] == true,
                        onCheckedChange = {
                            selectedTypes[type] = it
                            MultiModeListSelection.selectedTypes = selectedTypes
                                .filterValues { selected -> selected }
                                .keys
                                .toSet()
                        },
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
