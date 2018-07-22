/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.appspot.apprtc;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import org.appspot.apprtc.RoomParametersFetcher.RoomParametersFetcherEvents;
import org.appspot.apprtc.WebSocketChannelClient.WebSocketChannelEvents;
import org.appspot.apprtc.WebSocketChannelClient.WebSocketConnectionState;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

/**
 * Negotiates signaling for chatting with https://appr.tc "rooms".
 * Uses the client<->server specifics of the apprtc AppEngine webapp.
 *
 * <p>To use: create an instance of this object (registering a message handler) and
 * call connectToRoom().  Once room connection is established
 * onConnectedToRoom() callback with room parameters is invoked.
 * Messages to other party (with local Ice candidates and answer SDP) can
 * be sent after WebSocket connection is established.
 */
public class WebSocketRTCClient implements AppRTCClient, WebSocketChannelEvents {
  private static final String TAG = "WSRTCClient";
  private static final String ROOM_JOIN = "join";
  private static final String ROOM_MESSAGE = "message";
  private static final String ROOM_LEAVE = "leave";

  private enum ConnectionState { NEW, CONNECTED, CLOSED, ERROR }

  private enum MessageType { MESSAGE, LEAVE }

  private final Handler handler;
  private boolean initiator;
  private SignalingEvents events;
  private WebSocketChannelClient wsClient;
  private ConnectionState roomState;
  private RoomConnectionParameters connectionParameters;
  private String messageUrl;
  private String leaveUrl;
  private String sessionId;
  private String streamName;

  public WebSocketRTCClient(SignalingEvents events) {
    this.events = events;
    roomState = ConnectionState.NEW;
    final HandlerThread handlerThread = new HandlerThread(TAG);
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
  }

  // --------------------------------------------------------------------
  // AppRTCClient interface implementation.
  // Asynchronously connect to an AppRTC room URL using supplied connection
  // parameters, retrieves room parameters and connect to WebSocket server.
  @Override
  public void connectToRoom(RoomConnectionParameters connectionParameters) {
    this.connectionParameters = connectionParameters;
    handler.post(new Runnable() {
      @Override
      public void run() {
        connectToRoomInternal();
      }
    });
  }

  @Override
  public void disconnectFromRoom() {
    handler.post(new Runnable() {
      @Override
      public void run() {
        disconnectFromRoomInternal();
        handler.getLooper().quit();
      }
    });
  }

  // Connects to room - function runs on a local looper thread.
  private void connectToRoomInternal() {
//    String connectionUrl = getConnectionUrl(connectionParameters);
//    Log.d(TAG, "Connect to room: " + connectionUrl);
//    roomState = ConnectionState.NEW;
    wsClient = new WebSocketChannelClient(handler, this);
//
//    RoomParametersFetcherEvents callbacks = new RoomParametersFetcherEvents() {
//      @Override
//      public void onSignalingParametersReady(final SignalingParameters params) {
//        WebSocketRTCClient.this.handler.post(new Runnable() {
//          @Override
//          public void run() {
//            WebSocketRTCClient.this.signalingParametersReady(params);
//          }
//        });
//      }
//
//      @Override
//      public void onSignalingParametersError(String description) {
//        WebSocketRTCClient.this.reportError(description);
//      }
//    };
//
//    new RoomParametersFetcher(connectionUrl, null, callbacks).makeRequest();
    wsClient.connect("", "");
    wsClient.register("", "");
  }

  // Disconnect from room and send bye messages - runs on a local looper thread.
  private void disconnectFromRoomInternal() {
    Log.d(TAG, "Disconnect. Room state: " + roomState);
    if (roomState == ConnectionState.CONNECTED) {
      Log.d(TAG, "Closing room.");
      sendPostMessage(MessageType.LEAVE, leaveUrl, null);
    }
    roomState = ConnectionState.CLOSED;
    if (wsClient != null) {
      wsClient.disconnect(true);
    }
  }

  // Helper functions to get connection, post message and leave message URLs
  private String getConnectionUrl(RoomConnectionParameters connectionParameters) {
    return connectionParameters.roomUrl + "/" + ROOM_JOIN + "/" + connectionParameters.roomId
        + getQueryString(connectionParameters);
  }

  private String getMessageUrl(
      RoomConnectionParameters connectionParameters, SignalingParameters signalingParameters) {
    return connectionParameters.roomUrl + "/" + ROOM_MESSAGE + "/" + connectionParameters.roomId
        + "/" + signalingParameters.clientId + getQueryString(connectionParameters);
  }

  private String getLeaveUrl(
      RoomConnectionParameters connectionParameters, SignalingParameters signalingParameters) {
    return connectionParameters.roomUrl + "/" + ROOM_LEAVE + "/" + connectionParameters.roomId + "/"
        + signalingParameters.clientId + getQueryString(connectionParameters);
  }

  private String getQueryString(RoomConnectionParameters connectionParameters) {
    if (connectionParameters.urlParameters != null) {
      return "?" + connectionParameters.urlParameters;
    } else {
      return "";
    }
  }

  // Callback issued when room parameters are extracted. Runs on local
  // looper thread.
  private void signalingParametersReady(final SignalingParameters signalingParameters) {
    Log.d(TAG, "Room connection completed.");
    if (connectionParameters.loopback
        && (!signalingParameters.initiator || signalingParameters.offerSdp != null)) {
      reportError("Loopback room is busy.");
      return;
    }
    if (!connectionParameters.loopback && !signalingParameters.initiator
        && signalingParameters.offerSdp == null) {
      Log.w(TAG, "No offer SDP in room response.");
    }
    initiator = signalingParameters.initiator;
    messageUrl = getMessageUrl(connectionParameters, signalingParameters);
    leaveUrl = getLeaveUrl(connectionParameters, signalingParameters);
    Log.d(TAG, "Message URL: " + messageUrl);
    Log.d(TAG, "Leave URL: " + leaveUrl);
    roomState = ConnectionState.CONNECTED;

    // Fire connection and signaling parameters events.
    events.onConnectedToRoom(signalingParameters);

    // Connect and register WebSocket client.
    wsClient.connect(signalingParameters.wssUrl, signalingParameters.wssPostUrl);
    wsClient.register(connectionParameters.roomId, signalingParameters.clientId);
  }

  // Send local offer SDP to the other participant.
  @Override
  public void sendOfferSdp(final SessionDescription sdp) {
    handler.post(new Runnable() {
      @Override
      public void run() {
        if (roomState != ConnectionState.CONNECTED) {
          reportError("Sending offer SDP in non connected state.");
          return;
        }
        JSONObject json = new JSONObject();
        jsonPut(json, "sdp", sdp.description);
        jsonPut(json, "type", "offer");
        sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString());
        if (connectionParameters.loopback) {
          // In loopback mode rename this offer to answer and route it back.
          SessionDescription sdpAnswer = new SessionDescription(
              SessionDescription.Type.fromCanonicalForm("answer"), sdp.description);
          events.onRemoteDescription(sdpAnswer);
        }
      }
    });
  }

  // Send local answer SDP to the other participant.
  @Override
  public void sendAnswerSdp(final SessionDescription sdp) {
    handler.post(new Runnable() {
      @Override
      public void run() {
        if (connectionParameters.loopback) {
          Log.e(TAG, "Sending answer in loopback mode.");
          return;
        }
        JSONObject json = new JSONObject();

        jsonPut(json, "direction", "play");
        jsonPut(json, "command", "sendResponse");

        JSONObject streamInfo = new JSONObject();
        jsonPut(streamInfo, "applicationName", "webrtc/_definst_");
        jsonPut(streamInfo, "streamName", streamName);
        jsonPut(streamInfo, "sessionId", sessionId);
        jsonPut(json, "streamInfo", streamInfo);

        jsonPut(json, "streamInfo", streamInfo);

        JSONObject answer = new JSONObject();
        jsonPut(answer, "type", "answer");
        jsonPut(answer, "sdp", sdp.description);
        jsonPut(json, "sdp", answer);

        JSONObject child = new JSONObject();
        jsonPut(child, "param1", "value1");

        jsonPut(json, "userData", child);

        wsClient.send(json.toString());
      }
    });
  }

  // Send Ice candidate to the other participant.
  @Override
  public void sendLocalIceCandidate(final IceCandidate candidate) {
    handler.post(new Runnable() {
      @Override
      public void run() {
        JSONObject json = new JSONObject();
        jsonPut(json, "type", "candidate");
        jsonPut(json, "label", candidate.sdpMLineIndex);
        jsonPut(json, "id", candidate.sdpMid);
        jsonPut(json, "candidate", candidate.sdp);
//        if (initiator) {
//          // Call initiator sends ice candidates to GAE server.
//          if (roomState != ConnectionState.CONNECTED) {
//            reportError("Sending ICE candidate in non connected state.");
//            return;
//          }
//          wsClient.send(json.toString());
//          sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString());
//          if (connectionParameters.loopback) {
//            events.onRemoteIceCandidate(candidate);
//          }
//        } else {
          // Call receiver sends ice candidates to websocket server.
//          wsClient.send(json.toString());
//        }
      }
    });
  }

  // Send removed Ice candidates to the other participant.
  @Override
  public void sendLocalIceCandidateRemovals(final IceCandidate[] candidates) {
    handler.post(new Runnable() {
      @Override
      public void run() {
        JSONObject json = new JSONObject();
        jsonPut(json, "type", "remove-candidates");
        JSONArray jsonArray = new JSONArray();
        for (final IceCandidate candidate : candidates) {
          jsonArray.put(toJsonCandidate(candidate));
        }
        jsonPut(json, "candidates", jsonArray);
        if (initiator) {
          // Call initiator sends ice candidates to GAE server.
          if (roomState != ConnectionState.CONNECTED) {
            reportError("Sending ICE candidate removals in non connected state.");
            return;
          }
          sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString());
          if (connectionParameters.loopback) {
            events.onRemoteIceCandidatesRemoved(candidates);
          }
        } else {
          // Call receiver sends ice candidates to websocket server.
//          wsClient.send(json.toString());
        }
      }
    });
  }

  // --------------------------------------------------------------------
  // WebSocketChannelEvents interface implementation.
  // All events are called by WebSocketChannelClient on a local looper thread
  // (passed to WebSocket client constructor).
  @Override
  public void onWebSocketMessage(final String msg) {
    if (wsClient.getState() != WebSocketConnectionState.REGISTERED) {
      Log.e(TAG, "Got WebSocket message in non registered state.");
      return;
    }
    try {
      JSONObject json = new JSONObject(msg);

      JSONArray candidateArray = json.getJSONArray("iceCandidates");
      if (candidateArray != null) {
        for (int i = 0; i < candidateArray.length(); ++i) {
          IceCandidate cand = toJavaCandidate(candidateArray.getJSONObject(i));
          events.onRemoteIceCandidate(cand);
        }
      }
    } catch (JSONException e1) {
      try {
        JSONObject json = new JSONObject(msg);

        sessionId = new JSONObject(json.getString("streamInfo")).getString("sessionId");
        streamName = new JSONObject(json.getString("streamInfo")).getString("streamName");

        String type = new JSONObject(json.getString("sdp")).getString("type");
        if (type.equals("answer")) {
          SessionDescription sdp =
              new SessionDescription(SessionDescription.Type.fromCanonicalForm(type),
                  new JSONObject(json.getString("sdp")).getString("sdp"));
          events.onRemoteDescription(sdp);
        } else if (type.equals("offer")) {
          SessionDescription sdp =
              new SessionDescription(SessionDescription.Type.fromCanonicalForm(type),
                  new JSONObject(json.getString("sdp")).getString("sdp"));
          events.onRemoteDescription(sdp);
        }
      } catch (JSONException e2) {
        e2.printStackTrace();
      }
    }
  }

  @Override
  public void onWebSocketClose() {
    events.onChannelClose();
  }

  @Override
  public void onWebSocketError(String description) {
    reportError("WebSocket error: " + description);
  }

  // --------------------------------------------------------------------
  // Helper functions.
  private void reportError(final String errorMessage) {
    Log.e(TAG, errorMessage);
    handler.post(new Runnable() {
      @Override
      public void run() {
        if (roomState != ConnectionState.ERROR) {
          roomState = ConnectionState.ERROR;
          events.onChannelError(errorMessage);
        }
      }
    });
  }

  // Put a |key|->|value| mapping in |json|.
  private static void jsonPut(JSONObject json, String key, Object value) {
    try {
      json.put(key, value);
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
  }

  // Send SDP or ICE candidate to a room server.
  private void sendPostMessage(
      final MessageType messageType, final String url, final String message) {
    String logInfo = url;
    if (message != null) {
      logInfo += ". Message: " + message;
    }
    Log.d(TAG, "C->GAE: " + logInfo);
  }

  // Converts a Java candidate to a JSONObject.
  private JSONObject toJsonCandidate(final IceCandidate candidate) {
    JSONObject json = new JSONObject();
    jsonPut(json, "label", candidate.sdpMLineIndex);
    jsonPut(json, "id", candidate.sdpMid);
    jsonPut(json, "candidate", candidate.sdp);
    return json;
  }

  // Converts a JSON candidate to a Java object.
  private IceCandidate toJavaCandidate(JSONObject json) throws JSONException {
    return new IceCandidate(
        json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate"));
  }

}
