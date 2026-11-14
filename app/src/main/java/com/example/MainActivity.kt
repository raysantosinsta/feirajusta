package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.network.ConnectivityObserver
import com.example.data.repository.FeiraRepository
import com.example.data.supabase.SupabaseAuthManager
import com.example.data.supabase.SupabaseAuthState
import com.example.ui.auth.AuthScreen
import com.example.ui.components.SyncStatusPill
import com.example.ui.screens.CompareScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.ListsScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.theme.FeiraJustaTheme
import com.example.ui.viewmodel.FeiraViewModel
import kotlinx.coroutines.launch

const val ROUTE_COMPARE = "compare"
const val ROUTE_LISTS = "lists"
const val ROUTE_REGISTER = "register"
const val ROUTE_HISTORY = "history"

private data class FeiraNavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val feiraDestinations = listOf(
    FeiraNavDestination(ROUTE_COMPARE, R.string.nav_compare, Icons.AutoMirrored.Rounded.CompareArrows, Icons.AutoMirrored.Outlined.CompareArrows, "tab_compare"),
    FeiraNavDestination(ROUTE_LISTS, R.string.nav_lists, Icons.Rounded.Checklist, Icons.Outlined.Checklist, "tab_lists"),
    FeiraNavDestination(ROUTE_REGISTER, R.string.nav_register, Icons.Rounded.AddCircle, Icons.Outlined.AddCircleOutline, "tab_register"),
    FeiraNavDestination(ROUTE_HISTORY, R.string.nav_history, Icons.AutoMirrored.Rounded.ShowChart, Icons.AutoMirrored.Outlined.ShowChart, "tab_history")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FeiraJustaTheme {
                FeiraJustaRoot()
            }
        }
    }
}

@Composable
fun FeiraJustaRoot() {
    val context = LocalContext.current
    val authManager = remember(context) { SupabaseAuthManager.getInstance(context) }
    val authState by authManager.authState.collectAsStateWithLifecycle()

    when (val state = authState) {
        is SupabaseAuthState.Loading -> {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        is SupabaseAuthState.SignedOut -> {
            AuthScreen(
                authManager = authManager,
                onAuthSuccess = {}
            )
        }
        is SupabaseAuthState.SignedIn -> {
            key(state.session.userId) {
                FeiraMainScaffold(
                    authManager = authManager,
                    currentUserId = state.session.userId,
                    userDisplayName = state.session.displayName
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeiraMainScaffold(
    authManager: SupabaseAuthManager,
    currentUserId: String,
    userDisplayName: String
) {
    val scope = rememberCoroutineScope()

    val viewModel: FeiraViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                FeiraViewModel(
                    repository = FeiraRepository(app),
                    connectivityObserver = ConnectivityObserver(app),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val isRealtimeConnected by viewModel.isRealtimeConnected.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val pendingPrices by viewModel.pendingPrices.collectAsStateWithLifecycle()
    val marketsState by viewModel.marketsState.collectAsStateWithLifecycle()
    val unifiedPricesState by viewModel.unifiedPricesState.collectAsStateWithLifecycle()
    val comparisons by viewModel.comparisonSummaries.collectAsStateWithLifecycle()
    val quickTemplates by viewModel.quickProductTemplates.collectAsStateWithLifecycle()
    val spikeAlerts by viewModel.priceSpikeAlerts.collectAsStateWithLifecycle()
    val listsState by viewModel.shoppingListsState.collectAsStateWithLifecycle()
    val selectedListId by viewModel.selectedListId.collectAsStateWithLifecycle()
    val activeListItemsState by viewModel.activeListItemsState.collectAsStateWithLifecycle()
    val basketOptimization by viewModel.basketOptimization.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAppResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_COMPARE

    var historyTargetNormalized by rememberSaveable { mutableStateOf<String?>(null) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = userDisplayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        SyncStatusPill(
                            isOnline = isOnline,
                            pendingCount = pendingPrices.size,
                            isSyncing = isSyncing,
                            isRealtimeConnected = isRealtimeConnected,
                            onSyncClick = { viewModel.retryRefresh() }
                        )
                        IconButton(
                            onClick = { showSignOutDialog = true },
                            modifier = Modifier.testTag("sign_out_icon_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Logout,
                                contentDescription = stringResource(R.string.sign_out)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            floatingActionButton = {
                if (currentRoute == ROUTE_COMPARE) {
                    FloatingActionButton(
                        onClick = { navigateToTab(ROUTE_REGISTER) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("fab_quick_register")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Registrar novo preço")
                    }
                }
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        feiraDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        feiraDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_COMPARE,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ROUTE_COMPARE) {
                        CompareScreen(
                            pricesState = unifiedPricesState,
                            marketsState = marketsState,
                            comparisons = comparisons,
                            spikeAlerts = spikeAlerts,
                            onNavigateToScanner = { navigateToTab(ROUTE_REGISTER) },
                            onAddMarket = { name, neighborhood -> viewModel.createMarket(name, neighborhood) },
                            onAddProductToList = { name, unit -> viewModel.addItemToActiveList(name, 1L, unit) },
                            onInspectHistory = { normalized ->
                                historyTargetNormalized = normalized
                                navigateToTab(ROUTE_HISTORY)
                            },
                            onRetry = { viewModel.retryRefresh() },
                            isRefreshing = isRefreshing
                        )
                    }

                    composable(ROUTE_LISTS) {
                        ListsScreen(
                            listsState = listsState,
                            selectedListId = selectedListId,
                            itemsState = activeListItemsState,
                            basketOptimization = basketOptimization,
                            onFindAlternatives = { name, price ->
                                viewModel.findCategoryAlternatives(name, price)
                            },
                            onSelectList = { viewModel.selectList(it) },
                            onCreateList = { viewModel.createShoppingList(it) },
                            onJoinList = { viewModel.joinListByCode(it) },
                            onAddItem = { name, qty, unit -> viewModel.addItemToActiveList(name, qty, unit) },
                            onToggleItem = { viewModel.toggleListItem(it) },
                            onDeleteItem = { viewModel.deleteListItem(it) },
                            onRetry = { viewModel.retryRefresh() },
                            isRefreshing = isRefreshing
                        )
                    }

                    composable(ROUTE_REGISTER) {
                        ScannerScreen(
                            isOnline = isOnline,
                            isSyncing = isSyncing,
                            marketsState = marketsState,
                            pendingPrices = pendingPrices,
                            quickTemplates = quickTemplates,
                            onRegisterPrice = { p, b, c, u, cents, mId, mName, promo, photoPath, onDone ->
                                viewModel.registerPrice(p, b, c, u, cents, mId, mName, promo, photoPath, onDone)
                            },
                            onSyncNow = { viewModel.syncPendingPricesNow() }
                        )
                    }

                    composable(ROUTE_HISTORY) {
                        HistoryScreen(
                            pricesState = unifiedPricesState,
                            comparisons = comparisons,
                            initialProductNormalized = historyTargetNormalized,
                            onDeleteRecord = { recordId, photoUrl ->
                                viewModel.deletePriceRecord(recordId, photoUrl)
                            },
                            currentUserId = currentUserId,
                            isRefreshing = isRefreshing,
                            onRetry = { viewModel.retryRefresh() }
                        )
                    }
                }
            }
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sair do FeiraJusta?") },
            text = { Text("Seus registros sincronizados permanecerão salvos na sua conta Supabase.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        scope.launch { authManager.signOut() }
                    },
                    modifier = Modifier.testTag("confirm_sign_out_button")
                ) {
                    Text("Sair")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Continuar")
                }
            }
        )
    }
}
