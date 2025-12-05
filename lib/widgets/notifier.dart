import 'package:flutter/material.dart';

/// Utility to show a single SnackBar at a time, replacing any pending ones.
class Notifier {
  static final GlobalKey<ScaffoldMessengerState> messengerKey = GlobalKey<ScaffoldMessengerState>();

  static void show(BuildContext context, String message) {
    _showInternal(ScaffoldMessenger.of(context), message);
  }

  static void showGlobal(String message) {
    final messenger = messengerKey.currentState;
    if (messenger != null) {
      _showInternal(messenger, message);
    }
  }

  static void _showInternal(ScaffoldMessengerState messenger, String message) {
    messenger.clearSnackBars();
    messenger.showSnackBar(
      SnackBar(
        content: Text(message),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
}
