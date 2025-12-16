import 'package:flutter/services.dart';
import 'models.dart';

class SmartAgentApi {
  static const MethodChannel _channel = MethodChannel('com.smart/api');
  static const EventChannel _eventChannel = EventChannel('com.smart/events');
  static const EventChannel _updateChannel = EventChannel('com.smart/update_events');

  static Future<List<LocalTunnel>> getTunnels() async {
    final List<dynamic> result = await _channel.invokeMethod('getTunnels');
    return result.map((e) => LocalTunnel.fromMap(Map<String, dynamic>.from(e))).toList();
  }

  static Future<void> toggleTunnel(String fileName, bool active) async {
    await _channel.invokeMethod('toggleTunnel', {
      'file': fileName,
      'active': active,
    });
  }

  static Future<bool> importConfig() async {
    final result = await _channel.invokeMethod<bool>('importConfig');
    return result ?? false;
  }

  static Future<String?> getTunnelConfig(String fileName) async {
    return await _channel.invokeMethod<String>('getTunnelConfig', {'file': fileName});
  }

  static Future<bool> saveTunnelConfig(String fileName, String content, String tunnelName) async {
    final ok = await _channel.invokeMethod<bool>('saveTunnelConfig', {
      'file': fileName,
      'content': content,
      'tunnelName': tunnelName,
    });
    return ok ?? false;
  }

  static Future<bool> deleteTunnel(String fileName) async {
    final ok = await _channel.invokeMethod<bool>('deleteTunnel', {'file': fileName});
    return ok ?? false;
  }

  static Future<List<LogEntry>> getLogs({int limit = 10, int offset = 0}) async {
    final List<dynamic> result = await _channel.invokeMethod('getLogs', {
      'limit': limit,
      'offset': offset,
    });
    return result.map((e) => LogEntry.fromMap(Map<String, dynamic>.from(e))).toList();
  }

  static Future<List<LogEntry>> getDebugLogs({int limit = 10, int offset = 0}) async {
    final List<dynamic> result = await _channel.invokeMethod('getDebugLogs', {
      'limit': limit,
      'offset': offset,
    });
    return result.map((e) => LogEntry.fromMap(Map<String, dynamic>.from(e))).toList();
  }

  static Future<void> clearLogs() async {
    await _channel.invokeMethod('clearLogs');
  }

  static Future<List<String>> getSavedSsids() async {
    final List<dynamic> result = await _channel.invokeMethod('getSavedSsids');
    return result.map((e) => e.toString()).toList();
  }

  static Future<String?> getCurrentGatewayIp() async {
    return await _channel.invokeMethod<String>('getCurrentGatewayIp');
  }

  static Future<bool> requestLocationPermission() async {
    final granted = await _channel.invokeMethod<bool>('requestLocationPermission');
    return granted ?? false;
  }

  static Future<List<InstalledApp>> getInstalledApps() async {
    final List<dynamic> result = await _channel.invokeMethod('getInstalledApps');
    return result
        .map((e) => InstalledApp.fromMap(Map<String, dynamic>.from(e)))
        .toList();
  }

  static Future<Map<String, dynamic>> getSmartConfig() async {
    final result = await _channel.invokeMethod('getSmartConfig');
    return Map<String, dynamic>.from(result);
  }

  static Future<void> setAgentRuleConfig({
    required bool enabled,
    required List<AgentRule> rules,
  }) async {
    await _channel.invokeMethod('setAgentRuleConfig', {
      'enabled': enabled,
      'rules': rules.map((e) => e.toMap()).toList(),
    });
  }

  static Future<void> setAppRuleConfig({
    required bool appRuleEnabled,
    required List<String> selectedApps,
  }) async {
    await _channel.invokeMethod('setAppRuleConfig', {
      'appRuleEnabled': appRuleEnabled,
      'selectedApps': selectedApps,
    });
  }

  static Future<bool> isIgnoringBatteryOptimizations() async {
    final granted = await _channel.invokeMethod<bool>('isIgnoringBatteryOptimizations');
    return granted ?? false;
  }

  static Future<bool> requestIgnoreBatteryOptimizations() async {
    final granted = await _channel.invokeMethod<bool>('requestIgnoreBatteryOptimizations');
    return granted ?? false;
  }

  static Future<bool> openBatteryOptimizationSettings() async {
    final ok = await _channel.invokeMethod<bool>('openBatteryOptimizationSettings');
    return ok ?? false;
  }

  static Future<bool> openAutoStartSettings() async {
    final ok = await _channel.invokeMethod<bool>('openAutoStartSettings');
    return ok ?? false;
  }

  static Future<String> getAppVersion() async {
    final result = await _channel.invokeMethod<String>('getAppVersion');
    return result ?? '0.0.0';
  }

  static Future<UpdateInfo?> checkForUpdate() async {
    final result = await _channel.invokeMethod<Map>('checkForUpdate');
    if (result == null) return null;
    return UpdateInfo.fromMap(Map<String, dynamic>.from(result));
  }

  static Future<void> startUpdateDownload(String url, String version, {int? size, String? digest}) async {
    await _channel.invokeMethod('startUpdateDownload', {
      'url': url,
      'version': version,
      'size': size,
      'digest': digest,
    });
  }

  static Stream<VpnState> get vpnStateStream {
    return _eventChannel.receiveBroadcastStream().map((event) {
      return VpnState.fromMap(Map<String, dynamic>.from(event));
    });
  }

  static Stream<UpdateStatus> get updateStream {
    return _updateChannel.receiveBroadcastStream().map((event) {
      return UpdateStatus.fromMap(Map<String, dynamic>.from(event));
    });
  }
}
