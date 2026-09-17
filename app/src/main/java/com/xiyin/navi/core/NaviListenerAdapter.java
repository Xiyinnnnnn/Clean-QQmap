package com.xiyin.navi.core;

import com.tencent.map.navi.TencentRideNaviListener;
import com.tencent.map.navi.TencentWalkNaviListener;
import com.tencent.map.navi.data.AttachedLocation;
import com.tencent.map.navi.data.NaviTts;
import com.tencent.map.navi.data.NavigationData;
import com.tencent.map.navi.data.RouteData;

import java.util.ArrayList;

/**
 * 官方导航监听器适配：骑行/步行导航都以官方 listener 为准，
 * 这里只把「官方语音回调」转发给 Android 本机 TTS，并透出启停事件。
 */
public class NaviListenerAdapter implements TencentRideNaviListener, TencentWalkNaviListener {

    public interface Events {
        void onStarted();

        void onStopped();

        void onArrived();

        /** 绑路信息更新：用于偏航判定 */
        void onAttached(AttachedLocation attached);

        /** SDK 上报偏航 */
        void onOffRoute();
    }

    private final TtsSpeaker tts;
    private Events events;

    public NaviListenerAdapter(TtsSpeaker tts) {
        this.tts = tts;
    }

    public NaviListenerAdapter(TtsSpeaker tts, Events events) {
        this.tts = tts;
        this.events = events;
    }

    public void setEvents(Events events) {
        this.events = events;
    }

    /** 官方导航语音事件 → 本机 TTS。不自行生成任何导航文案。 */
    @Override
    public int onVoiceBroadcast(NaviTts naviTts) {
        if (tts != null) {
            tts.speak(naviTts);
        }
        return 0;
    }

    @Override
    public void onStartNavi() {
    }

    @Override
    public void onStopNavi() {
    }

    @Override
    public void onOffRoute() {
        if (events != null) {
            events.onOffRoute();
        }
    }

    @Override
    public void onArrivedDestination() {
    }

    @Override
    public void onUpdateAttachedLocation(AttachedLocation attachedLocation) {
        if (events != null) {
            events.onAttached(attachedLocation);
        }
    }

    @Override
    public void onGpsRssiChanged(int i) {
    }

    @Override
    public void onUpdateNavigationData(NavigationData navigationData) {
    }

    @Override
    public void onGpsWeakNotify() {
    }

    @Override
    public void onGpsStrongNotify() {
    }

    @Override
    public void onGpsStatusChanged(boolean b) {
    }

    @Override
    public void onUpdateCurrentRoute(RouteData routeData) {
    }

    @Override
    public void onChangeRes(boolean b) {
    }

    @Override
    public void onRecalculateRouteSuccess(ArrayList<RouteData> arrayList) {
    }

    @Override
    public void onDirectionUpdateBySensor(float v) {
    }
}
