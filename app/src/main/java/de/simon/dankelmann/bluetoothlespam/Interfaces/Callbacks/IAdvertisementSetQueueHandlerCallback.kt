package de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks

interface IAdvertisementSetQueueHandlerCallback {
    fun onQueueHandlerActivated()
    fun onQueueHandlerDeactivated()
    // Default no-op: starts a new Multi Mode cycle without changing the visible list yet.
    fun onMultiCycleStarted() {}
    // Default no-op: the new Multi Mode cycle is ready to replace the visible list.
    fun onMultiCycleReady() {}
    // Default no-op: only AdvertisementRoute cares about this one, unlike the other callbacks.
    fun onAdvertisementSetCollectionChanged() {}
}
