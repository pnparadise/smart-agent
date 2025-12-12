import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';

class Toast {
  static const MethodChannel _channel = MethodChannel('com.smart/toast');

  static Future<void> showText(BuildContext context, String msg) async {
    await _invoke('showText', msg);
  }

  static Future<void> showSuccess(BuildContext context, String msg) async {
    await _invoke('showSuccess', msg);
  }

  static Future<void> showFailure(BuildContext context, String msg) async {
    await _invoke('showFailure', msg);
  }

  static Future<void> showInfo(BuildContext context, String msg) async {
    await _invoke('showInfo', msg);
  }

  static Future<void> _invoke(String method, String msg) async {
    if (msg.isEmpty) return;
    try {
      await _channel.invokeMethod(method, {'msg': msg});
    } on PlatformException {
      // Native toast failure is non-fatal; ignore.
    }
  }
}
