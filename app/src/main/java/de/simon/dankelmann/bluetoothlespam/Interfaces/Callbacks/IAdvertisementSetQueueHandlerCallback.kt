package de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks

interface IAdvertisementSetQueueHandlerCallback {
    fun onQueueHandlerActivated()
    fun onQueueHandlerDeactivated()
    // Default no-op: Multi Mode clears the previous cycle's highlights before starting the next.
    fun onMultiCycleStarted() {}
    // Default no-op: only AdvertisementRoute cares about this one, unlike the other callbacks.
    fun onAdvertisementSetCollectionChanged() {}
}
