package com.zoffcc.applications.trifa;

import java.nio.charset.StandardCharsets;

/**
 * Minimal JNI contract for the toxcore binary. The native artifact expects this historical
 * class name; application code talks to it exclusively through ToxEngine.
 */
public final class MainActivity {
    public interface Events {
        void onSelfConnection(int status);
        void onFriendRequest(String publicKey, String message);
        void onFriendConnection(long friendNumber, int status);
        void onFriendMessage(long friendNumber, String message);
        void onLosslessPacket(long friendNumber, byte[] data);
    }
    public static volatile Events events;
    static { System.loadLibrary("jni-c-toxcore"); }

    public native void init(String dataDir, int udp, int localDiscovery, int orbot, String orbotHost,
        long orbotPort, String passphraseHash, int ipv6, int forceUdp, int videoBitrate,
        int maxQuantizer, int audioBitrate, int audioRate, int audioChannels);
    public static native void init_tox_callbacks();
    public static native long tox_iterate();
    public static native long tox_iteration_interval();
    public static native long tox_kill();
    public static native String get_my_toxid();
    public static native String tox_self_get_name();
    public static native int tox_self_set_name(String name);
    public static native int bootstrap_single(String ip, String key, long port);
    public static native int add_tcp_relay_single(String ip, String key, long port);
    public static native long tox_friend_add(String toxId, String message);
    public static native long tox_friend_add_norequest(String publicKey);
    public static native int tox_friend_delete(long friendNumber);
    public static native long tox_friend_by_public_key(String publicKey);
    public static native String tox_friend_get_public_key(long friendNumber);
    public static native String tox_friend_get_name(long friendNumber);
    public static native int tox_friend_get_connection_status(long friendNumber);
    public static native long[] tox_self_get_friend_list();
    public static native long tox_friend_send_message(long friendNumber, int type, String message);
    public static native int tox_friend_send_lossless_packet(long friendNumber, byte[] data, int length);
    public static native void update_savedata_file(String passphraseHash);

    public static void android_tox_callback_self_connection_status_cb_method(int s){ if(events!=null) events.onSelfConnection(s); }
    public static void android_tox_callback_friend_request_cb_method(String k,String m,long l){ if(events!=null) events.onFriendRequest(k,m); }
    public static void android_tox_callback_friend_connection_status_cb_method(long n,int s){ if(events!=null) events.onFriendConnection(n,s); }
    public static void android_tox_callback_friend_message_cb_method(long n,int t,String m,long l,byte[] h,long ts){ if(events!=null) events.onFriendMessage(n,m); }
    public static void android_tox_callback_friend_lossless_packet_cb_method(long n,byte[] d,long l){ if(events!=null) events.onLosslessPacket(n,d); }
    public static void android_tox_callback_friend_name_cb_method(long n,String s,long l){}
    public static void android_tox_callback_friend_status_message_cb_method(long n,String s,long l){}
    public static void android_tox_callback_friend_status_cb_method(long n,int s){}
    public static void android_tox_callback_friend_typing_cb_method(long n,int t){}
    public static void android_tox_callback_friend_read_receipt_message_v2_cb_method(long n,long s,byte[] m){}
    public static void android_tox_callback_friend_read_receipt_cb_method(long n,long m){}
    public static void android_tox_callback_friend_message_v2_cb_method(long n,String m,long l,long s,long ms,byte[] r,long rl){}
    public static void android_tox_callback_friend_sync_message_v2_cb_method(long n,long s,long ms,byte[] r,long rl,byte[] d,long dl){}
    public static void android_tox_callback_file_recv_control_cb_method(long a,long b,int c){}
    public static void android_tox_callback_file_chunk_request_cb_method(long a,long b,long c,long d){}
    public static void android_tox_callback_file_recv_cb_method(long a,long b,int c,long d,String e,long f){}
    public static void android_tox_callback_file_recv_chunk_cb_method(long a,long b,long c,byte[] d,long e){}
    public static void android_tox_callback_conference_connected_cb_method(long a){}
    public static void android_tox_callback_conference_invite_cb_method(long a,int b,byte[] c,long d){}
    public static void android_tox_callback_conference_message_cb_method(long a,long b,int c,String d,long e){}
    public static void android_tox_callback_conference_title_cb_method(long a,long b,String c,long d){}
    public static void android_tox_callback_conference_peer_name_cb_method(long a,long b,String c,long d){}
    public static void android_tox_callback_conference_peer_list_changed_cb_method(long a){}
    public static void android_tox_callback_conference_namelist_change_cb_method(long a,long b,int c){}
    public static void android_tox_callback_group_message_cb_method(long a,long b,int c,String d,long e,long f){}
    public static void android_tox_callback_group_private_message_cb_method(long a,long b,int c,String d,long e,long f){}
    public static void android_tox_callback_group_privacy_state_cb_method(long a,int b){}
    public static void android_tox_callback_group_invite_cb_method(long a,byte[] b,long c,String d){}
    public static void android_tox_callback_group_peer_join_cb_method(long a,long b){}
    public static void android_tox_callback_group_peer_exit_cb_method(long a,long b,int c){}
    public static void android_tox_callback_group_peer_name_cb_method(long a,long b){}
    public static void android_tox_callback_group_join_fail_cb_method(long a,int b){}
    public static void android_tox_callback_group_self_join_cb_method(long a){}
    public static void android_tox_callback_group_moderation_cb_method(long a,long b,long c,int d){}
    public static void android_tox_callback_group_connection_status_cb_method(long a,int b){}
    public static void android_tox_callback_group_topic_cb_method(long a,long b,String c,long d){}
    public static void android_tox_callback_group_custom_packet_cb_method(long a,long b,byte[] c,long d){}
    public static void android_tox_callback_group_custom_private_packet_cb_method(long a,long b,byte[] c,long d){}
    public static void android_tox_log_cb_method(int level,String file,long line,String function,String message){}
    public static String safe_string(byte[] value){ return value == null ? "" : new String(value, StandardCharsets.UTF_8); }
    public static void android_toxav_callback_call_cb_method(long a,int b,int c){}
    public static void android_toxav_callback_call_state_cb_method(long a,int b){}
    public static void android_toxav_callback_bit_rate_status_cb_method(long a,long b,long c){}
    public static void android_toxav_callback_call_comm_cb_method(long a,long b,long c){}
    public static void android_toxav_callback_video_receive_frame_cb_method(long a,long b,long c,long d,long e,long f){}
    public static void android_toxav_callback_video_receive_frame_pts_cb_method(long a,long b,long c,long d,long e,long f,long g){}
    public static void android_toxav_callback_video_receive_frame_h264_cb_method(long a,long b){}
    public static void android_toxav_callback_audio_receive_frame_cb_method(long a,long b,int c,long d){}
    public static void android_toxav_callback_audio_receive_frame_pts_cb_method(long a,long b,int c,long d,long e){}
    public static void android_toxav_callback_group_audio_receive_frame_cb_method(long a,long b,long c,int d,long e){}
}
