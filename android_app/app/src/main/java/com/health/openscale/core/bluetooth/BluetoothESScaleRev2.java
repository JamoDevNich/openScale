package com.health.openscale.core.bluetooth;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.lib.TrisaBodyAnalyzeLib;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothCentralCallback;
import com.welie.blessed.BluetoothPeripheral;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;

import timber.log.Timber;

public class BluetoothESScaleRev2 extends BluetoothCommunication {
    private static final int MANUFACTURER_DATA_ID = 0xFFFF;
    private static final int IDX_STATUS = 15;
    private static final int IDX_WEIGHT_MSB = 18;
    private static final int IDX_WEIGHT_LSB = 17;
    private static final int IDX_IMPEDANCE_MSB = 23;
    private static final int IDX_IMPEDANCE_LSB = 22;

    private BluetoothCentral central;
    private final BluetoothCentralCallback btCallback = new BluetoothCentralCallback() {
        @Override
        public void onDiscoveredPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull ScanResult scanResult) {
            SparseArray<byte[]> manufacturerSpecificData = scanResult.getScanRecord().getManufacturerSpecificData();
            byte[] data = scanResult.getScanRecord().getManufacturerSpecificData(MANUFACTURER_DATA_ID);

            if (data == null || data.length != 24) {
                Timber.d("Scale returned data shorter than 24 bytes");
                return;
            }

            if (data[IDX_STATUS] != 0b00100011){
                Timber.d("User measurement incomplete");
                return;
            }

            // Side note: Other variants of the scale may use a different multiplication factor such as 10f
            float weightKg = (float) decodeIntegerValue(data[IDX_WEIGHT_MSB], data[IDX_WEIGHT_LSB]) / 100f;
            float impedanceKohm = (float) decodeIntegerValue(data[IDX_IMPEDANCE_MSB], data[IDX_IMPEDANCE_LSB]) / 10f;

            Timber.d("Got weight: %f and impedance %f", weightKg, impedanceKohm);

            final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();
            TrisaBodyAnalyzeLib bodyAnalyzeLib = new TrisaBodyAnalyzeLib(scaleUser.getGender().isMale() ? 1 : 0, scaleUser.getAge(), (int)scaleUser.getBodyHeight());

            Timber.d("Fat: %f, Water: %f, Muscle: %f, Bone: %f",
                    bodyAnalyzeLib.getFat(weightKg, impedanceKohm),
                    bodyAnalyzeLib.getWater(weightKg, impedanceKohm),
                    bodyAnalyzeLib.getMuscle(weightKg, impedanceKohm),
                    bodyAnalyzeLib.getBone(weightKg, impedanceKohm)
            );

            ScaleMeasurement entry = new ScaleMeasurement();
            entry.setFat(bodyAnalyzeLib.getFat(weightKg, impedanceKohm));
            entry.setWater(bodyAnalyzeLib.getWater(weightKg, impedanceKohm));
            entry.setMuscle(bodyAnalyzeLib.getMuscle(weightKg, impedanceKohm));
            entry.setBone(bodyAnalyzeLib.getBone(weightKg, impedanceKohm));
            entry.setWeight(weightKg);
            addScaleMeasurement(entry);
            disconnect();
        }
    };

    private int decodeIntegerValue(byte a, byte b) {
        return ((a & 255) << 8) + (b & 255);
    }

    public BluetoothESScaleRev2(Context context)
    {
        super(context);
        central = new BluetoothCentral(context, btCallback, new Handler(Looper.getMainLooper()));
    }

    @Override
    public String driverName() {
        return "ESScale-Rev2";
    }

    @Override
    public void connect(String macAddress) {
        Timber.d("Mac address: %s", macAddress);
        List<ScanFilter> filters = new LinkedList<ScanFilter>();
        ScanFilter.Builder b = new ScanFilter.Builder();
        b.setDeviceAddress(macAddress);
        //b.setDeviceName("ADV");  // There is no name broadcast from the device
        b.setManufacturerData(MANUFACTURER_DATA_ID, null, null);
        filters.add(b.build());
        central.scanForPeripheralsUsingFilters(filters);
    }

    @Override
    public void disconnect() {
        if (central != null)
            central.stopScan();
        central = null;
        super.disconnect();
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        return false;
    }
}
