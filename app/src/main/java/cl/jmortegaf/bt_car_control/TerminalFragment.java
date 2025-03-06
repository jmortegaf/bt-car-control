package cl.jmortegaf.bt_car_control;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayDeque;
import java.util.Arrays;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;

    private TextView statusText;
//    private TextView sendText;
//    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    // Commands
    private final String cmd_open_doors = "80631db2265d125";
    private final String cmd_close_doors = "43e34197b9602f5";
    private final String cmd_open_trunk = "a8940b14d160917";
    private final String cmd_start_acc = "d070aabd259b5f8";
    private final String cmd_start_ign = "7a99cf5e884e365";
    private final String cmd_start = "fc6b94af3e0d8bd";
    private final String enable_system ="92iflevntsx43s1";
    private final String disable_system = "hmwhvk4fiyn2wdr";
    private boolean system_status;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        system_status = true;;

        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        statusText = view.findViewById(R.id.status_text);
        statusText.setTextColor(getResources().getColor(R.color.colorRecieveText));

        View unlock_doors_btn=view.findViewById(R.id.unlock_doors_btn);
        unlock_doors_btn.setOnClickListener(v->send(cmd_open_doors));

        View unlock_trunk_btn=view.findViewById(R.id.unlock_trunk_btn);
        unlock_trunk_btn.setOnClickListener(v->send(cmd_open_trunk));

        View lock_doors_btn=view.findViewById(R.id.lock_doors_btn);
        lock_doors_btn.setOnClickListener(v->send(cmd_close_doors));

        View start_acc_btn=view.findViewById(R.id.acc_btn);
        start_acc_btn.setOnClickListener(v->send(cmd_start_acc));

        View ignition_btn=view.findViewById(R.id.ignition_btn);
        ignition_btn.setOnClickListener(v->send(cmd_start_ign));

        View start_stop_btn = view.findViewById(R.id.start_stop_btn);
        start_stop_btn.setOnClickListener(v->send(cmd_start));

        View enable_disable_btn = view.findViewById(R.id.enable_disable_btn);
        TextView enable_disable_text = (TextView) view.findViewById(R.id.enable_disable_text);

        enable_disable_btn.setOnClickListener(v -> {
            if(system_status){
                ((ImageButton)enable_disable_btn).setBackgroundResource(R.drawable.disabled_btn);
                enable_disable_text.setText(R.string.system_disabled);
                send(disable_system);
                system_status=false;
            }
            else{
                ((ImageButton)enable_disable_btn).setBackgroundResource(R.drawable.enabled_btn);
                enable_disable_text.setText(R.string.system_enabled);
                send(enable_system);
                system_status=true;
            }
        });


        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true);
            menu.findItem(R.id.backgroundNotification).setEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            statusText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
//            sendText.setText("");
//            hexWatcher.enable(hexEnabled);
//            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                } else {
                    showNotificationSettings();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

//    private void receive(ArrayDeque<byte[]> datas) {
//        SpannableStringBuilder spn = new SpannableStringBuilder();
//        for (byte[] data : datas) {
//            if (hexEnabled) {
//                spn.append(TextUtil.toHexString(data)).append('\n');
//            } else {
//                String msg = new String(data);
//                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
//                    // don't show CR as ^M if directly before LF
//                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
//                    // special handling if CR and LF come in separate fragments
//                    if (pendingNewline && msg.charAt(0) == '\n') {
//                        if(spn.length() >= 2) {
//                            spn.delete(spn.length() - 2, spn.length());
//                        } else {
//                            Editable edt = receiveText.getEditableText();
//                            if (edt != null && edt.length() >= 2)
//                                edt.delete(edt.length() - 2, edt.length());
//                        }
//                    }
//                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
//                }
//                spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
//            }
//        }
//        receiveText.append(spn);
//    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//        receiveText.append(spn);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(Arrays.equals(permissions, new String[]{Manifest.permission.POST_NOTIFICATIONS}) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled())
            showNotificationSettings();
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
//        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
//        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

}
