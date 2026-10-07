package io.github.magnusencoded.stationtostation.features.tour

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.data.DeviceLocation
import io.github.magnusencoded.stationtostation.ui.LocalExchangeScreen
import kotlinx.coroutines.launch

/** The Exchange screen while the **Tour** is at S7: one row for the friend, no radio. */
@Composable
fun TourExchangeScreen(viewModel: AppViewModel, onBack: () -> Unit, onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    val name = remember { viewModel.tour.exchangeFriendName.orEmpty() }
    var connecting by remember { mutableStateOf(false) }
    val exchange: () -> Unit = {
        scope.launch {
            try {
                viewModel.tour.exchangeWithFriend()
                if (viewModel.state.value.tour.step == TourStep.S8) onConnected()
            } finally {
                connecting = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { exchange() }
    LocalExchangeScreen(name, connecting, onBack) {
        connecting = true
        scope.launch {
            if (viewModel.tour.askLocationOnce()) permission.launch(DeviceLocation.requiredPermissions())
            else exchange()
        }
    }
}
