import 'dart:typed_data';

enum RuleType {
  wifiSsid,
  wifiGateway,
  ipv6Available,
  ipv4Available;

  String get nativeValue {
    switch (this) {
      case RuleType.wifiSsid:
        return 'WIFI_SSID';
      case RuleType.wifiGateway:
        return 'WIFI_GATEWAY';
      case RuleType.ipv6Available:
        return 'IPV6_AVAILABLE';
      case RuleType.ipv4Available:
        return 'IPV4_AVAILABLE';
    }
  }

  String get label {
    return labelZh;
  }

  String get labelZh {
    switch (this) {
      case RuleType.wifiSsid:
        return 'WiFi 名称';
      case RuleType.wifiGateway:
        return 'WiFi 网关';
      case RuleType.ipv6Available:
        return 'IPv6 可用';
      case RuleType.ipv4Available:
        return 'IPv4 可用';
    }
  }

  String get labelEn {
    switch (this) {
      case RuleType.wifiSsid:
        return 'WiFi SSID';
      case RuleType.wifiGateway:
        return 'WiFi Gateway';
      case RuleType.ipv6Available:
        return 'IPv6 Available';
      case RuleType.ipv4Available:
        return 'IPv4 Available';
    }
  }
}

class AgentRule {
  final String id;
  final RuleType type;
  final String? value;
  final String tunnelFile;
  final String tunnelName;
  final bool enabled;

  AgentRule({
    required this.id,
    required this.type,
    this.value,
    required this.tunnelFile,
    required this.tunnelName,
    required this.enabled,
  });

  factory AgentRule.fromMap(Map<String, dynamic> map) {
    final file = (map['tunnelFile'] ?? '') as String;
    final displayRaw = (map['tunnelName'] ?? file) as String;
    final display = displayRaw.isEmpty && file.isEmpty ? '直连' : displayRaw;
    return AgentRule(
      id: map['id'] as String,
      type: RuleTypeParser.fromNative(map['type'] as String),
      value: map['value'] as String?,
      tunnelFile: file,
      tunnelName: display,
      enabled: map['enabled'] as bool,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'type': type.nativeValue,
      'value': value,
      'tunnelFile': tunnelFile,
      'tunnelName': tunnelName,
      'enabled': enabled,
    };
  }
}

class LocalTunnel {
  final String name;
  final String file;

  LocalTunnel({required this.name, required this.file});

  factory LocalTunnel.fromMap(Map<String, dynamic> map) {
    return LocalTunnel(
      name: map['name'] as String,
      file: map['file'] as String,
    );
  }
}

class InstalledApp {
  final String name;
  final String package;
  final Uint8List? iconBytes;

  InstalledApp({required this.name, required this.package, this.iconBytes});

  factory InstalledApp.fromMap(Map<String, dynamic> map) {
    return InstalledApp(
      name: map['name'] as String,
      package: map['package'] as String,
      iconBytes: map['icon'] as Uint8List?,
    );
  }
}

class DohConfig {
  final bool enabled;
  final String dohUrl;

  DohConfig({required this.enabled, required this.dohUrl});

  factory DohConfig.fromMap(Map<String, dynamic> map) {
    return DohConfig(
      enabled: map['enabled'] as bool? ?? false,
      dohUrl: map['dohUrl'] as String? ?? '',
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'enabled': enabled,
      'dohUrl': dohUrl,
    };
  }
}

class DohPreset {
  final String label;
  final String url;

  const DohPreset(this.label, this.url);
}

extension RuleTypeParser on RuleType {
  static RuleType fromNative(String value) {
    switch (value.toUpperCase()) {
      case 'WIFI_SSID':
        return RuleType.wifiSsid;
      case 'WIFI_GATEWAY':
        return RuleType.wifiGateway;
      case 'IPV6_AVAILABLE':
        return RuleType.ipv6Available;
      case 'IPV4_AVAILABLE':
        return RuleType.ipv4Available;
      default:
        return RuleType.wifiSsid;
    }
  }
}

class VpnState {
  final bool isRunning;
  final String? tunnelName;
  final String? tunnelFile;
  final String? lastHandshake;
  final int txBytes;
  final int rxBytes;
  final int? appRuleVersion;

  VpnState({
    required this.isRunning,
    this.tunnelName,
    this.tunnelFile,
    this.lastHandshake,
    this.txBytes = 0,
    this.rxBytes = 0,
    this.appRuleVersion,
  });

  factory VpnState.fromMap(Map<String, dynamic> map) {
    return VpnState(
      isRunning: map['isRunning'] as bool,
      tunnelName: map['tunnelName'] as String?,
      tunnelFile: map['tunnelFile'] as String?,
      lastHandshake: map['lastHandshake'] as String?,
      txBytes: (map['txBytes'] as num?)?.toInt() ?? 0,
      rxBytes: (map['rxBytes'] as num?)?.toInt() ?? 0,
      appRuleVersion: map['appRuleVersion'] as int?,
    );
  }
}

class LogEntry {
  final DateTime timestamp;
  final String message;

  LogEntry({required this.timestamp, required this.message});

  factory LogEntry.fromMap(Map<String, dynamic> map) {
    return LogEntry(
      timestamp: DateTime.fromMillisecondsSinceEpoch((map['timestamp'] as num).toInt()),
      message: map['message'] as String,
    );
  }
}

class UpdateInfo {
  final String version;
  final String downloadUrl;
  final int? size;
  final String? digest;

  UpdateInfo({
    required this.version,
    required this.downloadUrl,
    this.size,
    this.digest,
  });

  factory UpdateInfo.fromMap(Map<String, dynamic> map) {
    return UpdateInfo(
      version: map['version'] as String,
      downloadUrl: map['downloadUrl'] as String,
      size: (map['size'] as num?)?.toInt(),
      digest: map['digest'] as String?,
    );
  }
}

class UpdateStatus {
  final String state; // idle, downloading, installing, error
  final int progress;
  final String? latestVersion;
  final String? message;

  UpdateStatus({
    required this.state,
    this.progress = 0,
    this.latestVersion,
    this.message,
  });

  bool get isActive => state == 'downloading' || state == 'installing';

  factory UpdateStatus.fromMap(Map<String, dynamic> map) {
    return UpdateStatus(
      state: map['state'] as String,
      progress: (map['progress'] as num?)?.toInt() ?? 0,
      latestVersion: map['latestVersion'] as String?,
      message: map['message'] as String?,
    );
  }
}
