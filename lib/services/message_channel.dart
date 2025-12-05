import 'package:flutter/services.dart';

class MessageChannel {
  static const EventChannel _channel = EventChannel('com.smart/messages');

  static Stream<String> get messages {
    return _channel.receiveBroadcastStream().map((event) {
      if (event is Map) {
        final msg = event['message'];
        if (msg != null) return msg.toString();
      } else if (event != null) {
        return event.toString();
      }
      return '';
    });
  }
}
