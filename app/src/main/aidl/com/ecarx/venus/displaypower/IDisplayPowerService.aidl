package com.ecarx.venus.displaypower;

import com.ecarx.venus.displaypower.IDisplayPowerServiceListener;

interface IDisplayPowerService {
    boolean setDisplayPowerState(int displayId, int displayState);
    int getDisplayPowerState(int displayId);
    boolean isSupportDisplayTouchPower(int displayId);
    boolean subscribe(IDisplayPowerServiceListener listener);
    boolean unsubscribe(IDisplayPowerServiceListener listener);
}
