package com.ecarx.venus.displaypower;

interface IDisplayPowerServiceListener {
    oneway void notifyDisplayPowerStateChanged(int displayId, int newStatus);
}
