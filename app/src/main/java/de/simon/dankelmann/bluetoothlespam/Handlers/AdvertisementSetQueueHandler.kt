package de.simon.dankelmann.bluetoothlespam.Handlers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityActionModalAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityIos17CrashAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityNewAirtagPopUpAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityNewDevicePopUpAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityNotYourDevicePopUpAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementError
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementQueueMode
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementSetType
import de.simon.dankelmann.bluetoothlespam.Enums.TxPowerLevel
import de.simon.dankelmann.bluetoothlespam.Helpers.QueueHandlerHelpers
import de.simon.dankelmann.bluetoothlespam.Helpers.BluetoothHelpers
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IAdvertisementServiceCallback
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IAdvertisementSetQueueHandlerCallback
import de.simon.dankelmann.bluetoothlespam.Interfaces.Services.IAdvertisementService
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSet
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSetCollection
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSetList
import de.simon.dankelmann.bluetoothlespam.Services.AdvertisementForegroundService
import de.simon.dankelmann.bluetoothlespam.R
import kotlin.random.Random

/**
 * Handler that takes an advertisement set, and iterates over the set according to a given AdvertisementQueueMode.
 *
 * The job of this handler is to select the next set, and provide it to the IAdvertisementService.
 *
 * The UI code should drive the advertising via this handler (via start, stop, set advertisement set, set queue mode).
 * This handler takes care of starting and stopping services as appropriate.
 */
class AdvertisementSetQueueHandler(
    context: Context,
    adService: IAdvertisementService,
) : IAdvertisementServiceCallback {

    private class MultiAdvertiser(
        val service: IAdvertisementService,
        val callback: IAdvertisementServiceCallback,
        val cycleToken: Int,
    ) {
        var stopHandled = false
        var onStopped: (() -> Unit)? = null
        var startAttempts = 0
    }

    private var _logTag = "AdvertisementSetQueueHandler"

    private var _advertisementService: IAdvertisementService = adService

    private var _advertisementQueueMode: AdvertisementQueueMode = AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_LINEAR
    private var _advertisementSetCollection: AdvertisementSetCollection =
        AdvertisementSetCollection()
    private var _intervalMillis: Long = QueueHandlerHelpers.getInterval(context)
    private var _txPowerLevel: TxPowerLevel? = null

    // Callbacks to listen to events of the underlying advertisement service
    private var _advertisementServiceCallbacks:MutableList<IAdvertisementServiceCallback> = mutableListOf()
    // Callbacks to listen to queue events
    private var _advertisementQueueHandlerCallbacks:MutableList<IAdvertisementSetQueueHandlerCallback> = mutableListOf()

    private var _active = false
    private var _currentAdvertisementSet: AdvertisementSet? = null
    private var _currentAdvertisementSetListIndex = 0
    private var _currentAdvertisementSetIndex = 0
    private val _multiAdvertisementServices = mutableListOf<MultiAdvertiser>()
    private val _multiListIndices = mutableMapOf<Int, Int>()
    private var _multiCycleToken = 0

    init {
        _advertisementService.addAdvertisementServiceCallback(this)
    }

    fun isActive(): Boolean {
        return _active
    }

    fun setAdvertisementQueueMode(advertisementQueueMode: AdvertisementQueueMode){
        // A running Multi Mode cycle must finish in Multi Mode. Changing this enum while the
        // parallel advertisers are alive makes the UI/notification switch to single-queue logic
        // while the old advertisers continue running.
        if (_active &&
            _advertisementQueueMode == AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_MULTI &&
            advertisementQueueMode != AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_MULTI
        ) {
            return
        }
        _advertisementQueueMode = advertisementQueueMode
    }

    fun getAdvertisementQueueMode():AdvertisementQueueMode{
        return _advertisementQueueMode
    }

    fun setInterval(milliseconds: Long) {
        if (milliseconds > 0) {
            _intervalMillis = milliseconds
        }
    }

    fun setTxPowerLevel(txPowerLevel: TxPowerLevel) {
        _txPowerLevel = txPowerLevel
        _advertisementService.setTxPowerLevel(txPowerLevel)
        _multiAdvertisementServices.forEach { it.service.setTxPowerLevel(txPowerLevel) }
    }

    fun getTxPowerLevel(): TxPowerLevel = _txPowerLevel ?: _advertisementService.getTxPowerLevel()

    fun setAdvertisementService(advertisementService: IAdvertisementService) {
        _advertisementService.removeAdvertisementServiceCallback(this)

        _advertisementService = advertisementService
        _advertisementService.addAdvertisementServiceCallback(this)
    }


    fun setSelectedAdvertisementSet(advertisementSetListIndex: Int, advertisementSetIndex: Int){
        val advertisementSet = _advertisementSetCollection.advertisementSetLists[advertisementSetListIndex].advertisementSets.get(advertisementSetIndex)
        _currentAdvertisementSetListIndex = advertisementSetListIndex
        _currentAdvertisementSetIndex = advertisementSetIndex
        _currentAdvertisementSet = advertisementSet
    }

    fun setAdvertisementSetCollection(advertisementSetCollection: AdvertisementSetCollection){
        if(_advertisementSetCollection != advertisementSetCollection){
            _advertisementSetCollection = advertisementSetCollection
        }

        // Reset indices
        _currentAdvertisementSet= null
        _currentAdvertisementSetListIndex = 0
        _currentAdvertisementSetIndex = 0
        _multiListIndices.clear()

        // Callers must invoke this from the main thread (matches every existing call site) since
        // listeners (e.g. AdvertisementRoute) update Compose/LiveData state directly here.
        _advertisementQueueHandlerCallbacks.forEach {
            try {
                it.onAdvertisementSetCollectionChanged()
            } catch (e: Exception) {
                Log.e(_logTag, "Failed to call onAdvertisementSetCollectionChanged: ${e.message}")
            }
        }
    }

    fun getAdvertisementSetCollection(): AdvertisementSetCollection{
        return _advertisementSetCollection
    }

    // Add / Remove AdvertisementSetCollections
    fun clearAdvertisementSetCollection(){
        _advertisementSetCollection.advertisementSetLists.clear()
    }
    fun addAdvertisementSetList(advertisementSetList: AdvertisementSetList){
        if(!_advertisementSetCollection.advertisementSetLists.contains(advertisementSetList)){
            _advertisementSetCollection.advertisementSetLists.add(advertisementSetList)
        }
    }

    fun removeAdvertisementSetList(advertisementSetList: AdvertisementSetList){
        if(_advertisementSetCollection.advertisementSetLists.contains(advertisementSetList)){
            _advertisementSetCollection.advertisementSetLists.remove(advertisementSetList)
        }
    }

    // Add / Remove Callbacks
    fun addAdvertisementServiceCallback(callback: IAdvertisementServiceCallback){
        if(!_advertisementServiceCallbacks.contains(callback)){
            _advertisementServiceCallbacks.add(callback)
        }
    }
    fun removeAdvertisementServiceCallback(callback: IAdvertisementServiceCallback){
        if(_advertisementServiceCallbacks.contains(callback)){
            _advertisementServiceCallbacks.remove(callback)
        }
    }

    fun addAdvertisementQueueHandlerCallback(callback: IAdvertisementSetQueueHandlerCallback){
        if(!_advertisementQueueHandlerCallbacks.contains(callback)){
            _advertisementQueueHandlerCallbacks.add(callback)
        }
    }
    fun removeAdvertisementQueueHandlerCallback(callback: IAdvertisementSetQueueHandlerCallback){
        if(_advertisementQueueHandlerCallbacks.contains(callback)){
            _advertisementQueueHandlerCallbacks.remove(callback)
        }
    }

    fun hasCheckedItems(): Boolean {
        // Check if any advertisement set is checked
        for (list in _advertisementSetCollection.advertisementSetLists) {
            for (set in list.advertisementSets) {
                if (set.isChecked) {
                    return true
                }
            }
        }
        return false
    }

    fun toggle(context: Context) {
        if (_active) {
            deactivate(context)
        } else {
            activate(context)
        }
    }

    fun activate(context: Context) {
        if (_active) {
            return
        }

        // Cannot activate anything if nothing is selected
        if (!hasCheckedItems()) {
            Toast.makeText(context, R.string.toast_no_items_selected, Toast.LENGTH_SHORT).show()
            return
        }

        _active = true
        AdvertisementForegroundService.startService(context)
        _advertisementQueueHandlerCallbacks.forEach { it ->
            try {
                it.onQueueHandlerActivated()
            } catch (e: Exception) {
                Log.e(_logTag, "Failed to call onQueueHandlerActivated: ${e.message}")
            }
        }
        if (_advertisementQueueMode == AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_MULTI) {
            advertiseNextMultiCycle(context)
        } else {
            advertiseNextAdvertisementSet()
        }
    }

    fun deactivate(context: Context, stopService: Boolean = false) {
        _active = false
        _multiCycleToken++
        _multiHandler.removeCallbacksAndMessages(null)
        stopCurrentMultiAdvertisers()
        _multiAdvertisementServices.clear()

        _advertisementService.stopAdvertisement()

        if (stopService) {
            Log.d(_logTag, "Stopping Foreground Service")
            AdvertisementForegroundService.stopService(context)
        }

        _advertisementQueueHandlerCallbacks.forEach { it ->
            try {
                it.onQueueHandlerDeactivated()
            } catch (e: Exception) {
                Log.e(_logTag, "Failed to call onQueueHandlerDeactivated: ${e.message}")
            }
        }
    }

    private fun advertiseNextAdvertisementSet() {
        selectNextAdvertisementSet()

        val nextSet = _currentAdvertisementSet
        if (nextSet == null) {
            Log.e(_logTag, "Current Advertisement Set is null.")
            return
        }

        if (_active) {
            // Only advertise if the set is checked
            if (nextSet.isChecked) {
                val preparedSet = prepareAdvertisementSet(nextSet)
                _advertisementService.startAdvertisement(preparedSet)
            } else {
                // If the set is not checked, immediately move to the next one
                Log.d(_logTag, "Skipping unchecked advertisement set: ${nextSet.title}")
                onAdvertisementSucceeded()
            }
        }
    }

    /** Starts one checked item from every checked list at the same time. */
    private fun advertiseNextMultiCycle(context: Context) {
        if (!_active || _advertisementQueueMode != AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_MULTI) return

        val cycleToken = ++_multiCycleToken
        stopCurrentMultiAdvertisers {
            if (_active &&
                _advertisementQueueMode == AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_MULTI &&
                cycleToken == _multiCycleToken
            ) {
                startMultiCycle(context, cycleToken)
            }
        }
    }

    private fun startMultiCycle(context: Context, cycleToken: Int) {
        if (!_active || cycleToken != _multiCycleToken) return

        val selectedSets = _advertisementSetCollection.advertisementSetLists.mapIndexedNotNull { listIndex, list ->
            val checked = list.advertisementSets.filter { it.isChecked }
            if (checked.isEmpty()) return@mapIndexedNotNull null

            val selectedIndex = if (_advertisementQueueMode == AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_RANDOM) {
                Random.nextInt(checked.size)
            } else {
                val next = _multiListIndices[listIndex] ?: 0
                _multiListIndices[listIndex] = (next + 1) % checked.size
                next % checked.size
            }
            prepareAdvertisementSet(checked[selectedIndex])
        }

        _advertisementQueueHandlerCallbacks.forEach { callback ->
            try { callback.onMultiCycleStarted() } catch (e: Exception) {
                Log.e(_logTag, "Failed to call onMultiCycleStarted: ${e.message}")
            }
        }

        if (selectedSets.isEmpty()) return

        selectedSets.forEachIndexed { index, set ->
            val service = BluetoothHelpers.getAdvertisementService(context)
            _txPowerLevel?.let { service.setTxPowerLevel(it) }
            lateinit var advertiser: MultiAdvertiser
            val callback = object : IAdvertisementServiceCallback {
                override fun onAdvertisementSetStart(advertisementSet: AdvertisementSet?) {
                    if (advertiser.cycleToken == _multiCycleToken) forwardMultiStart(advertisementSet)
                }
                override fun onAdvertisementSetStop(advertisementSet: AdvertisementSet?) {
                    if (advertiser.cycleToken == _multiCycleToken) forwardMultiStop(advertisementSet)
                    advertiser.onStopped?.invoke()
                }
                override fun onAdvertisementSetSucceeded(advertisementSet: AdvertisementSet?) {
                    if (advertiser.cycleToken == _multiCycleToken) forwardMultiSuccess(advertisementSet)
                }
                override fun onAdvertisementSetFailed(advertisementSet: AdvertisementSet?, advertisementError: AdvertisementError) {
                    if (advertiser.cycleToken != _multiCycleToken) return

                    // A phone can reject a burst of parallel starts while it is still
                    // allocating advertising slots. Keep that item queued and retry it
                    // instead of dropping it from the cycle.
                    if (advertisementError == AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ||
                        advertisementError == AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR
                    ) {
                        scheduleMultiAdvertiserRetry(advertiser, set)
                    } else {
                        forwardMultiFailure(advertisementSet, advertisementError)
                    }
                }
            }
            advertiser = MultiAdvertiser(service, callback, cycleToken)
            service.addAdvertisementServiceCallback(callback)
            _multiAdvertisementServices.add(advertiser)

            // Stagger requests slightly so slower Bluetooth stacks can allocate each
            // advertising slot before the next request arrives.
            _multiHandler.postDelayed({
                if (advertiser.cycleToken == _multiCycleToken && _active) {
                    startMultiAdvertiser(advertiser, set)
                }
            }, index * MULTI_START_STAGGER_MILLIS)
        }

        _multiHandler.postDelayed({
            if (_active &&
                _advertisementQueueMode == AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_MULTI &&
                cycleToken == _multiCycleToken
            ) advertiseNextMultiCycle(context)
        }, _intervalMillis)
    }

    private val _multiHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val MULTI_START_STAGGER_MILLIS = 50L
        const val MULTI_RETRY_DELAY_MILLIS = 500L
        const val MULTI_MAX_START_ATTEMPTS = 5
    }

    private fun startMultiAdvertiser(
        advertiser: MultiAdvertiser,
        set: AdvertisementSet,
    ) {
        if (!_active || advertiser.cycleToken != _multiCycleToken || advertiser.stopHandled) return
        advertiser.startAttempts++
        advertiser.service.startAdvertisement(set)
    }

    private fun scheduleMultiAdvertiserRetry(
        advertiser: MultiAdvertiser,
        set: AdvertisementSet,
    ) {
        if (advertiser.startAttempts >= MULTI_MAX_START_ATTEMPTS) {
            forwardMultiFailure(set, AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
            return
        }

        _multiHandler.postDelayed({
            startMultiAdvertiser(advertiser, set)
        }, MULTI_RETRY_DELAY_MILLIS)
    }

    /** Stops the current advertisers and waits for Android's stop callbacks before restarting. */
    private fun stopCurrentMultiAdvertisers(onComplete: (() -> Unit)? = null) {
        val advertisers = _multiAdvertisementServices.toList()
        _multiAdvertisementServices.clear()
        if (advertisers.isEmpty()) {
            onComplete?.invoke()
            return
        }

        var remaining = advertisers.size
        var completed = false
        fun completeOne(advertiser: MultiAdvertiser) {
            if (advertiser.stopHandled) return
            advertiser.stopHandled = true
            advertiser.service.removeAdvertisementServiceCallback(advertiser.callback)
            remaining--
            if (remaining == 0 && !completed) {
                completed = true
                onComplete?.invoke()
            }
        }

        advertisers.forEach { advertiser ->
            advertiser.onStopped = { completeOne(advertiser) }
            advertiser.service.stopAdvertisement()
        }

        // Some adapters do not deliver a stop callback when an advertiser failed to start.
        // Do not block the next cycle forever in that case.
        _multiHandler.postDelayed({
            if (!completed) {
                completed = true
                advertisers.forEach { completeOne(it) }
                onComplete?.invoke()
            }
        }, 1000L)
    }

    private fun forwardMultiStart(set: AdvertisementSet?) {
        _advertisementServiceCallbacks.forEach { callback ->
            try { callback.onAdvertisementSetStart(set) } catch (e: Exception) { Log.e(_logTag, "Error in multi start callback", e) }
        }
    }

    private fun forwardMultiStop(set: AdvertisementSet?) {
        _advertisementServiceCallbacks.forEach { callback ->
            try { callback.onAdvertisementSetStop(set) } catch (e: Exception) { Log.e(_logTag, "Error in multi stop callback", e) }
        }
    }

    private fun forwardMultiSuccess(set: AdvertisementSet?) {
        _advertisementServiceCallbacks.forEach { callback ->
            try { callback.onAdvertisementSetSucceeded(set) } catch (e: Exception) { Log.e(_logTag, "Error in multi success callback", e) }
        }
    }

    private fun forwardMultiFailure(set: AdvertisementSet?, error: AdvertisementError) {
        _advertisementServiceCallbacks.forEach { callback ->
            try { callback.onAdvertisementSetFailed(set, error) } catch (e: Exception) { Log.e(_logTag, "Error in multi failure callback", e) }
        }
    }

    private fun prepareAdvertisementSet(advertisementSet: AdvertisementSet): AdvertisementSet {
        return when (advertisementSet.type) {
            AdvertisementSetType.ADVERTISEMENT_TYPE_CONTINUITY_NEW_DEVICE -> ContinuityNewDevicePopUpAdvertisementSetGenerator.prepareAdvertisementSet(advertisementSet)
            AdvertisementSetType.ADVERTISEMENT_TYPE_CONTINUITY_NEW_AIRTAG -> ContinuityNewAirtagPopUpAdvertisementSetGenerator.prepareAdvertisementSet(advertisementSet)
            AdvertisementSetType.ADVERTISEMENT_TYPE_CONTINUITY_NOT_YOUR_DEVICE -> ContinuityNotYourDevicePopUpAdvertisementSetGenerator.prepareAdvertisementSet(advertisementSet)
            AdvertisementSetType.ADVERTISEMENT_TYPE_CONTINUITY_ACTION_MODALS -> ContinuityActionModalAdvertisementSetGenerator.prepareAdvertisementSet(advertisementSet)
            AdvertisementSetType.ADVERTISEMENT_TYPE_CONTINUITY_IOS_17_CRASH -> ContinuityIos17CrashAdvertisementSetGenerator.prepareAdvertisementSet(advertisementSet)
            else -> advertisementSet
        }
    }

    /**
     * Select the AdvertisementSet that should be advertised next.
     *
     * Precondition: at least one set is checked by the user.
     * The case of nothing being checked should be handled earlier.
     * If nothing is checked, this function will do nothing.
     */
    private fun selectNextAdvertisementSet() {
        // Explicit returns are used for clarity

        when (_advertisementQueueMode) {
            AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_LINEAR -> {
                // If no AdvertisementSet is currently selected, make sure to start at the beginning
                if (_currentAdvertisementSet == null) {
                    _currentAdvertisementSetListIndex = 0
                    _currentAdvertisementSetIndex = 0
                }

                val selectedList =
                    _advertisementSetCollection.advertisementSetLists[_currentAdvertisementSetListIndex]
                Log.d(
                    _logTag,
                    "List: ${selectedList.title}, SETS: ${selectedList.advertisementSets.count()}, CurrentIndex: $_currentAdvertisementSetIndex"
                )

                // Find the next checked item in the current list
                for (i in (_currentAdvertisementSetIndex + 1) until selectedList.advertisementSets.size) {
                    if (selectedList.advertisementSets[i].isChecked) {
                        // _currentAdvertisementSetListIndex is unchanged
                        _currentAdvertisementSetIndex = i
                        _currentAdvertisementSet = selectedList.advertisementSets[i]
                        return
                    }
                }

                // If we didn't find a checked item in the current list, move to the next list
                // Find the next list with checked items
                val startListIndex = _currentAdvertisementSetListIndex
                val numberOfLists = _advertisementSetCollection.advertisementSetLists.size

                // Loop through lists starting from the next one
                for (listOffset in 1..numberOfLists) {
                    val listIndex = (startListIndex + listOffset) % numberOfLists
                    val list = _advertisementSetCollection.advertisementSetLists[listIndex]

                    // Find the first checked item in this list
                    val firstCheckedIndex = list.advertisementSets.indexOfFirst { it.isChecked }
                    if (firstCheckedIndex >= 0) {
                        _currentAdvertisementSetListIndex = listIndex
                        _currentAdvertisementSetIndex = firstCheckedIndex
                        _currentAdvertisementSet = list.advertisementSets[firstCheckedIndex]
                        return
                    }
                }

                // No checked set found in any list
                return
            }

            AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_RANDOM -> {
                // Create a list of all checked advertisement sets across all lists
                // TODO: Cache this, don't recompute it all the time?
                val checkedSets = mutableListOf<Triple<Int, Int, AdvertisementSet>>()

                _advertisementSetCollection.advertisementSetLists.forEachIndexed { listIndex, list ->
                    list.advertisementSets.forEachIndexed { setIndex, set ->
                        if (set.isChecked) {
                            checkedSets.add(Triple(listIndex, setIndex, set))
                        }
                    }
                }

                // If we have checked items, randomly select one of them
                if (checkedSets.isNotEmpty()) {
                    val randomIndex = Random.nextInt(checkedSets.size)
                    val selected = checkedSets[randomIndex]
                    _currentAdvertisementSetListIndex = selected.first
                    _currentAdvertisementSetIndex = selected.second
                    _currentAdvertisementSet = selected.third
                } else {
                    // If no checked items, do nothing
                }
            }

            AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_MULTI -> Unit
        }
    }

    private fun onAdvertisementSucceeded() {
        _advertisementService.stopAdvertisement()

        if (_advertisementService.isLegacyService()) {
            advertiseNextAdvertisementSet()
        } else {
            // Wait for the Stop Advertising Callback
        }
    }

    private fun onAdvertisementFailed() {
        Log.d(_logTag, "Advertisement failed, trying again")
        onAdvertisementSucceeded()
    }

    private fun runLocalCallback(success:Boolean){
        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                if(success){
                    onAdvertisementSucceeded()
                } else {
                    onAdvertisementFailed()
                }
            }
        }, _intervalMillis)
    }

    // Callback Implementation, just pass to own Listeners
    override fun onAdvertisementSetStart(advertisementSet: AdvertisementSet?) {
        _advertisementServiceCallbacks.map {
            try {
                it.onAdvertisementSetStart(advertisementSet)
            } catch (e:Exception){
                Log.e(_logTag, "Error in: onAdvertisementSetStart ${e.message}")
            }
        }
    }

    override fun onAdvertisementSetStop(advertisementSet: AdvertisementSet?) {
        _advertisementServiceCallbacks.map {
            try {
                it.onAdvertisementSetStop(advertisementSet)
            } catch (e:Exception){
                Log.e(_logTag, "Error in: onAdvertisementSetStop ${e.message}")
            }
        }

        if (!_advertisementService.isLegacyService()) {
            advertiseNextAdvertisementSet()
        }
    }

    override fun onAdvertisementSetSucceeded(advertisementSet: AdvertisementSet?) {
        runLocalCallback(true)
        _advertisementServiceCallbacks.map {
            try {
                it.onAdvertisementSetSucceeded(advertisementSet)
            } catch (e:Exception){
                Log.e(_logTag, "Error in: onAdvertisementSetSucceeded ${e.message}")
            }
        }
    }

    override fun onAdvertisementSetFailed(advertisementSet: AdvertisementSet?, advertisementError: AdvertisementError) {
        runLocalCallback(false)
        _advertisementServiceCallbacks.map {
            try {
                it.onAdvertisementSetFailed(advertisementSet, advertisementError)
            } catch (e:Exception){
                Log.e(_logTag, "Error in: onAdvertisementSetFailed ${e.message}")
            }
        }
    }
}
