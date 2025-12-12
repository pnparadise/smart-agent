import 'dart:async';

import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../api.dart';
import '../models.dart';
import '../theme.dart';
import 'app_select.dart';
import 'log_page.dart';
import '../widgets/toast.dart';

class SmartRulesScreen extends StatefulWidget {
  const SmartRulesScreen({super.key});

  @override
  State<SmartRulesScreen> createState() => _SmartRulesScreenState();
}

class _SmartRulesScreenState extends State<SmartRulesScreen> with WidgetsBindingObserver {
  bool _enabled = false;
  bool _appRuleEnabled = false;
  List<String> _selectedApps = [];
  List<AgentRule> _rules = [];
  List<LocalTunnel> _tunnels = [];

  // 编辑状态
  RuleType _formType = RuleType.wifiSsid;
  final TextEditingController _ssidController = TextEditingController();
  String? _formTunnel;
  String? _editingRuleId;
  bool _isAddingNew = false;

  bool _loading = true;
  bool _batteryProtected = false;
  bool _pendingBatteryRefresh = false;
  bool _pendingBatterySettingsAfterRequest = false;
  String _appVersion = '-';
  UpdateStatus _updateStatus = UpdateStatus(state: 'idle');
  bool _checkingUpdate = false;
  StreamSubscription<UpdateStatus>? _updateSub;

  // 滚动控制相关
  final ScrollController _scrollController = ScrollController();
  final GlobalKey _tempItemKey = GlobalKey();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadConfig();
    _listenUpdates();
    _loadAppVersion();
  }

  @override
  void dispose() {
    _ssidController.dispose();
    _scrollController.dispose();
    WidgetsBinding.instance.removeObserver(this);
    _updateSub?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _pendingBatteryRefresh) {
      _handlePendingBatteryRefresh();
    }
    super.didChangeAppLifecycleState(state);
  }

  // --- Helpers ---

  Widget _buildToggleSwitch({
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Switch(
      value: value,
      onChanged: onChanged,
      activeColor: Colors.white,
      inactiveThumbColor: Colors.white,
      activeTrackColor: AppTheme.vultrBlue,
      inactiveTrackColor: const Color(0xFFE6E9EF),
      trackOutlineColor: MaterialStateProperty.all(Colors.transparent),
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      thumbIcon: MaterialStateProperty.resolveWith((states) {
        if (states.contains(MaterialState.selected)) {
          return const Icon(Icons.check, size: 12, color: AppTheme.vultrBlue);
        }
        return null;
      }),
    );
  }

  Widget _buildDragHandle() {
    const double dotSize = 3.5;
    const double gap = 4;
    return SizedBox(
      width: 24,
      height: 24,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: List.generate(3, (row) {
          return Padding(
            padding: EdgeInsets.only(bottom: row == 2 ? 0 : gap),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: List.generate(4, (_) {
                return Container(
                  width: dotSize,
                  height: dotSize,
                  decoration: const BoxDecoration(
                    color: Color(0xFF8A8A8A),
                    shape: BoxShape.circle,
                  ),
                );
              }),
            ),
          );
        }),
      ),
    );
  }

  // --- Logic ---

  Future<void> _loadConfig() async {
    try {
      final config = await SmartAgentApi.getSmartConfig();
      final tunnels = await SmartAgentApi.getTunnels();
      final ignoringBatteryOpt = await SmartAgentApi.isIgnoringBatteryOptimizations();
      if (mounted) {
        setState(() {
          _enabled = config['enabled'] as bool;
          _appRuleEnabled = config['appRuleEnabled'] as bool? ?? false;
          _selectedApps = (config['selectedApps'] as List?)?.cast<String>() ?? [];
          _rules = (config['rules'] as List)
              .map((e) => AgentRule.fromMap(Map<String, dynamic>.from(e)))
              .toList();
          _tunnels = tunnels;
          _batteryProtected = ignoringBatteryOpt;
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _loadAppVersion() async {
    final version = await SmartAgentApi.getAppVersion();
    if (mounted) setState(() => _appVersion = version);
  }

  void _listenUpdates() {
    _updateSub = SmartAgentApi.updateStream.listen((event) {
      if (!mounted) return;
      setState(() => _updateStatus = event);
      if (event.state == 'error' && event.message != null) {
        Toast.showFailure(context, event.message!);
      }
    });
  }

  Future<void> _refreshBatteryStatus() async {
    final ignoring = await SmartAgentApi.isIgnoringBatteryOptimizations();
    if (mounted) setState(() => _batteryProtected = ignoring);
  }

  Future<void> _handlePendingBatteryRefresh() async {
    _pendingBatteryRefresh = false;
    await _refreshBatteryStatus();
    if (_pendingBatterySettingsAfterRequest) {
      _pendingBatterySettingsAfterRequest = false;
      if (!_batteryProtected) {
        await _openBatterySettingsAndQueueRefresh();
      }
    }
  }

  Future<void> _saveAgentRuleConfig() async {
    final validRules = _rules.where((r) => r.id != 'TEMP_NEW_RULE').toList();
    await SmartAgentApi.setAgentRuleConfig(
      enabled: _enabled,
      rules: validRules,
    );
    if (mounted && !_isAddingNew) {
      setState(() => _rules = validRules);
    }
  }

  Future<void> _saveAppRuleConfig() async {
    await SmartAgentApi.setAppRuleConfig(
      appRuleEnabled: _appRuleEnabled,
      selectedApps: _selectedApps,
    );
  }

  Future<void> _handleVersionTap() async {
    if (_checkingUpdate || _updateStatus.isActive) return;
    setState(() => _checkingUpdate = true);
    try {
      final info = await SmartAgentApi.checkForUpdate();
      if (!mounted) return;
      if (info == null) {
        Toast.showFailure(context, '检查更新失败,请稍后重试');
        return;
      }
      if (info.version == _appVersion) {
        Toast.showSuccess(context, '已是最新版本');
        return;
      }
      final alreadyDownloaded = _updateStatus.latestVersion == info.version && _updateStatus.progress >= 100;
      setState(() {
        _updateStatus = alreadyDownloaded
            ? UpdateStatus(state: 'idle', progress: 100, latestVersion: info.version)
            : UpdateStatus(state: 'downloading', latestVersion: info.version);
      });
      await SmartAgentApi.startUpdateDownload(
        info.downloadUrl,
        info.version,
        size: info.size,
        digest: info.digest,
      );
    } finally {
      if (mounted) setState(() => _checkingUpdate = false);
    }
  }

  void _startNewRule() {
    if (_editingRuleId != null) {
      if (_editingRuleId == 'TEMP_NEW_RULE') {
        _cancelEdit();
        return;
      }
      setState(() {
        _editingRuleId = null;
        _ssidController.clear();
      });
    }

    final newRule = AgentRule(
      id: 'TEMP_NEW_RULE',
      type: RuleType.wifiSsid,
      value: '',
      tunnelFile: '',
      tunnelName: '直连',
      enabled: true,
    );

    setState(() {
      _isAddingNew = true;
      _rules.add(newRule);
    });

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        setState(() {
          _editingRuleId = newRule.id;
          _formType = RuleType.wifiSsid;
          _ssidController.text = '';
          _formTunnel = _tunnels.isNotEmpty ? _tunnels.first.file : null;
        });

        // 核心修改逻辑：
        // 1. 先让 AnimatedSize 跑完动画（UI中设定是300ms）。
        // 2. 设置 350ms 的延时，确保高度已经完全撑开。
        // 3. alignment: 1.0 强制将卡片的底部对齐到屏幕的底部。
        Future.delayed(const Duration(milliseconds: 200), () {
          if (_scrollController.hasClients) {
            _scrollController.animateTo(
              _scrollController.position.maxScrollExtent, // 滚动到底部，自然露出底部的 60px 留白
              duration: const Duration(milliseconds: 400),
              curve: Curves.easeOutCubic,
            );
          }
        });
      }
    });
  }

  void _startEdit(AgentRule rule) {
    if (_editingRuleId != null && _editingRuleId != rule.id) {
      _cancelEdit();
    }

    setState(() {
      _editingRuleId = rule.id;
      _formType = rule.type;
      _ssidController.text = rule.value ?? '';

      final file = rule.tunnelFile;
      final candidate = file.isEmpty ? null : file;
      final exists = candidate != null && _tunnels.any((t) => t.file == candidate);
      _formTunnel = exists ? candidate : null;
    });
  }

  void _cancelEdit() {
    if (_isAddingNew && _editingRuleId == 'TEMP_NEW_RULE') {
      setState(() {
        _editingRuleId = null;
      });
      Future.delayed(const Duration(milliseconds: 300), () {
        if (mounted && _isAddingNew) {
          setState(() {
            _rules.removeWhere((r) => r.id == 'TEMP_NEW_RULE');
            _isAddingNew = false;
          });
        }
      });
    } else {
      setState(() {
        _editingRuleId = null;
        _isAddingNew = false;
        _ssidController.clear();
      });
    }
  }

  void _submitRule() {
    if (_formType == RuleType.wifiSsid) {
      final ssid = _ssidController.text.trim();
      if (ssid.isEmpty) {
        Toast.showFailure(context, '请填写WiFi SSID');
        return;
      }
    }

    final tunnelFile = _formTunnel ?? '';
    final tunnelName = _formTunnel == null
        ? '直连'
        : _tunnels.firstWhere(
          (t) => t.file == _formTunnel,
      orElse: () => LocalTunnel(name: _formTunnel ?? '', file: _formTunnel ?? ''),
    ).name;

    final finalId = (_isAddingNew || _editingRuleId == 'TEMP_NEW_RULE')
        ? DateTime.now().millisecondsSinceEpoch.toString()
        : _editingRuleId!;

    final rule = AgentRule(
      id: finalId,
      type: _formType,
      value: _formType == RuleType.wifiSsid ? _ssidController.text.trim() : null,
      tunnelFile: tunnelFile,
      tunnelName: tunnelName,
      enabled: true,
    );

    setState(() {
      final idx = _rules.indexWhere((r) => r.id == _editingRuleId);
      if (idx != -1) {
        _rules[idx] = rule;
      } else {
        _rules.add(rule);
      }
      _editingRuleId = null;
      _isAddingNew = false;
      _ssidController.clear();
    });
    _saveAgentRuleConfig();
  }

  // --- UI ---

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Center(child: CircularProgressIndicator());

    return Scaffold(
      backgroundColor: const Color(0xFFF2F4F7),
      body: CustomScrollView(
        controller: _scrollController,
        slivers: [
          SliverToBoxAdapter(child: _buildAppRuleCard()),
          SliverToBoxAdapter(child: _buildAgentRuleCard()),

          if (_enabled)
            SliverReorderableList(
              onReorder: (oldIndex, newIndex) {
                if (_editingRuleId != null) return;

                if (oldIndex < newIndex) newIndex -= 1;
                setState(() {
                  final item = _rules.removeAt(oldIndex);
                  _rules.insert(newIndex, item);
                });
                _saveAgentRuleConfig();
              },
              itemCount: _rules.length,
              itemBuilder: (context, index) {
                final rule = _rules[index];
                return _buildRuleItem(rule, index);
              },
            ),

          if (_enabled && !_isAddingNew)
            SliverToBoxAdapter(
              child: _buildAddButton(),
            ),

          SliverToBoxAdapter(child: _buildBatteryOptimizationCard()),
          SliverToBoxAdapter(child: _buildAutoStartCard()),
          SliverToBoxAdapter(child: _buildVersionCard()),

          const SliverToBoxAdapter(child: SizedBox(height: 60)),
        ],
      ),
    );
  }

  Widget _buildAppRuleCard() {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 16, 16, 12),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 22),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        boxShadow: AppTheme.cardShadow,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('App Split Tunneling', style: GoogleFonts.inter(fontSize: 15, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 4),
                    Text(
                      '仅勾选的应用流量走隧道',
                      style: GoogleFonts.inter(color: AppTheme.textSecondary, fontSize: 13),
                    ),
                  ],
                ),
              ),
              _buildToggleSwitch(
                value: _appRuleEnabled,
                onChanged: (val) {
                  setState(() => _appRuleEnabled = val);
                  _saveAppRuleConfig();
                },
              )
            ],
          ),
          if (_appRuleEnabled) ...[
            const SizedBox(height: 12),
            ElevatedButton.icon(
              onPressed: () async {
                final result = await Navigator.of(context).push<List<String>>(
                  MaterialPageRoute(
                    builder: (_) => AppSelectPage(selected: _selectedApps),
                  ),
                );
                if (result != null) {
                  setState(() => _selectedApps = result);
                  _saveAppRuleConfig();
                }
              },
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.white,
                foregroundColor: AppTheme.textPrimary,
                elevation: 0,
                side: const BorderSide(color: AppTheme.divider),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              ),
              icon: const Icon(Icons.playlist_add_check_outlined, color: AppTheme.vultrBlue),
              label: Text(
                _selectedApps.isEmpty ? '选择应用' : '已选 ${_selectedApps.length} 个应用',
                style: GoogleFonts.inter(fontWeight: FontWeight.w600),
              ),
            ),
          ]
        ],
      ),
    );
  }

  Widget _buildBatteryOptimizationCard() {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        boxShadow: AppTheme.cardShadow,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Persistent Protection',
                  style: GoogleFonts.inter(fontSize: 15, fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 4),
                Text(
                  '关闭电池优化 避免数据断连',
                  style: GoogleFonts.inter(color: AppTheme.textSecondary, fontSize: 13),
                ),
              ],
            ),
          ),
          _buildToggleSwitch(
            value: _batteryProtected,
            onChanged: (val) async {
              if (val) {
                final granted = await SmartAgentApi.requestIgnoreBatteryOptimizations();
                if (!granted) {
                  _pendingBatteryRefresh = true;
                  _pendingBatterySettingsAfterRequest = true;
                } else {
                  await _refreshBatteryStatus();
                  if (!_batteryProtected && mounted) {
                    Toast.showFailure(context, 'Please grant the permission to keep Smart Agent active.');
                  }
                }
              } else {
                await _openBatterySettingsAndQueueRefresh();
              }
            },
          )
        ],
      ),
    );
  }

  Widget _buildAutoStartCard() {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        boxShadow: AppTheme.cardShadow,
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: () async {
            final ok = await SmartAgentApi.openAutoStartSettings();
            if (!ok && mounted) {
              Toast.showFailure(context, 'Please enable auto start in system settings.');
            }
          },
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Disable Power Guard',
                        style: GoogleFonts.inter(fontSize: 15, fontWeight: FontWeight.w700),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '允许自启与后台活动 保持连接稳定',
                        style: GoogleFonts.inter(color: AppTheme.textSecondary, fontSize: 13),
                      ),
                    ],
                  ),
                ),
                const Icon(Icons.arrow_forward_ios, size: 16, color: AppTheme.textSecondary),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildVersionCard() {
    final latest = _updateStatus.latestVersion;
    final hasNewReady = latest != null && latest != _appVersion && !_updateStatus.isActive;
    final isBusy = _updateStatus.isActive || (_checkingUpdate && !hasNewReady);
    // Only show numeric progress when actively downloading/installing; keep spinner without text otherwise
    final showNumericProgress = _updateStatus.isActive &&
        _updateStatus.progress > 0 &&
        _updateStatus.progress < 100;
    final progressText = showNumericProgress ? '${_updateStatus.progress}%' : null;
    final subtitle = () {
      if (_updateStatus.isActive) {
        return '正在更新 ${latest ?? ""}'.trim();
      }
      if (hasNewReady) {
        return '可更新至最新版本 $latest';
      }
      return '检查/更新最新版本';
    }();
    Widget trailing;
    if (isBusy) {
      trailing = SizedBox(
        width: 32,
        height: 32,
        child: Stack(
          alignment: Alignment.center,
          children: [
            CircularProgressIndicator(
              strokeWidth: 3,
              valueColor: const AlwaysStoppedAnimation(AppTheme.vultrBlue),
              value: showNumericProgress ? _updateStatus.progress / 100 : null,
            ),
            if (progressText != null)
              Text(
                progressText,
                style: GoogleFonts.inter(fontSize: 10, fontWeight: FontWeight.w700, color: AppTheme.vultrBlue),
              ),
          ],
        ),
      );
    } else {
      trailing = const Icon(Icons.arrow_forward_ios, size: 16, color: AppTheme.textSecondary);
    }

    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        boxShadow: AppTheme.cardShadow,
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: _handleVersionTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Build Version $_appVersion',
                        style: GoogleFonts.inter(fontSize: 15, fontWeight: FontWeight.w700),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        subtitle,
                        style: GoogleFonts.inter(color: AppTheme.textSecondary, fontSize: 13),
                      ),
                    ],
                  ),
                ),
                trailing,
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _openBatterySettingsAndQueueRefresh() async {
    final ok = await SmartAgentApi.openBatteryOptimizationSettings();
    if (ok) {
      _pendingBatteryRefresh = true;
    } else if (mounted) {
      Toast.showFailure(context, 'Open system settings to change battery optimization.');
    }
  }

  Widget _buildAgentRuleCard() {
    return Container(
      width: double.infinity,
      margin:  const EdgeInsets.fromLTRB(16, 0, 16, 12),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 22),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        boxShadow: AppTheme.cardShadow,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Smart Agent',
                  style: GoogleFonts.inter(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                    color: AppTheme.textPrimary,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  '按规则从上到下匹配隧道',
                  style: GoogleFonts.inter(
                    fontSize: 13,
                    color: AppTheme.textSecondary,
                  ),
                ),
              ],
            ),
          ),
          _buildToggleSwitch(
            value: _enabled,
            onChanged: (val) {
              setState(() => _enabled = val);
              _saveAgentRuleConfig();
              if (val) {
                SmartAgentApi.requestLocationPermission();
              }
            },
          ),
        ],
      ),
    );
  }

  Widget _buildRuleItem(AgentRule rule, int index) {
    final isEditing = rule.id == _editingRuleId;
    final isTemp = rule.id == 'TEMP_NEW_RULE';
    const double verticalPadding = 10;

    Widget dragHandleWidget = Container(
      color: Colors.transparent,
      padding: const EdgeInsets.fromLTRB(12, 16, 0, 16),
      child: _buildDragHandle(),
    );

    return Container(
      key: ValueKey(rule.id),
      // 修改处：如果是新增的临时规则 (isTemp)，下边距设为 0，与 Add Button 保持一致
      // 否则保持 8，用于规则之间的分隔
      margin: EdgeInsets.fromLTRB(16, 0, 16, isTemp ? 12 : 8),
      decoration: BoxDecoration(
        boxShadow: isEditing ? AppTheme.cardShadow : null,
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Material(
          // 如果是新增的临时规则，绑定 GlobalKey，确保滚动定位准确
          key: isTemp ? _tempItemKey : null,
          color: Colors.white,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: verticalPadding),
                onTap: () {
                  if (isEditing) {
                    _cancelEdit();
                  } else {
                    _startEdit(rule);
                  }
                },
                leading: Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: AppTheme.background,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(
                    isTemp
                        ? Icons.playlist_add
                        : (rule.type == RuleType.wifiSsid ? Icons.wifi : Icons.public),
                    color: isEditing ? AppTheme.vultrBlue : AppTheme.textSecondary,
                    size: 24,
                  ),
                ),
                title: Text(
                  isTemp
                      ? 'Add Tunnel Rule'
                      : (rule.type == RuleType.wifiSsid ? (rule.value?.isEmpty ?? true ? '配置 WiFi' : 'WiFi: ${rule.value}') : rule.type.label),
                  style: GoogleFonts.inter(
                    fontWeight: FontWeight.w700,
                    fontSize: 15,
                    color: AppTheme.textPrimary,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: isTemp
                    ? Text(
                  '新增隧道匹配规则',
                  style: GoogleFonts.inter(
                    fontWeight: FontWeight.w500,
                    fontSize: 13,
                    color: AppTheme.textSecondary,
                  ),
                )
                    : Text(
                  'Target: ${rule.tunnelName}',
                  style: GoogleFonts.inter(
                    fontSize: 13,
                    color: AppTheme.textSecondary,
                  ),
                ),
                trailing: isTemp
                    ? null
                    : (isEditing
                    ? null
                    : Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      icon: const Icon(Icons.edit_outlined, size: 20),
                      color: AppTheme.textSecondary,
                      onPressed: () => _startEdit(rule),
                    ),
                    _editingRuleId != null
                        ? Opacity(opacity: 0.0, child: dragHandleWidget)
                        : ReorderableDragStartListener(
                      index: index,
                      child: dragHandleWidget,
                    ),
                  ],
                )),
                onLongPress: (isEditing || isTemp) ? null : () async {
                  final confirm = await showDialog<bool>(
                    context: context,
                    builder: (ctx) => AlertDialog(
                      backgroundColor: Colors.white,
                      title: const Text('删除规则'),
                      content: const Text('确定删除该规则吗？'),
                      actions: [
                        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
                        TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('删除')),
                      ],
                    ),
                  );
                  if (confirm == true) {
                    setState(() {
                      _rules.removeWhere((r) => r.id == rule.id);
                    });
                    _saveAgentRuleConfig();
                  }
                },
              ),

              // 展开编辑区域
              AnimatedSize(
                duration: const Duration(milliseconds: 300),
                curve: Curves.easeInOutCubic,
                alignment: Alignment.topCenter,
                child: isEditing
                    ? Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Divider(height: 1, thickness: 1, color: Color(0xFFF2F4F7)),
                      const SizedBox(height: 16),

                      Row(
                        children: [
                          Expanded(
                            child: DropdownButtonFormField<RuleType>(
                              value: _formType,
                              isExpanded: true,
                              isDense: true,
                              decoration: InputDecoration(
                                labelText: '条件类型',
                                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                                contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                              ),
                              items: RuleType.values.map((t) {
                                return DropdownMenuItem(value: t, child: Text(t.label));
                              }).toList(),
                              onChanged: (val) {
                                if (val != null) setState(() => _formType = val);
                              },
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: DropdownButtonFormField<String?>(
                              value: _tunnels.any((t) => t.file == _formTunnel) ? _formTunnel : null,
                              isExpanded: true,
                              isDense: true,
                              decoration: InputDecoration(
                                labelText: '目标隧道',
                                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                                contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                              ),
                              items: [
                                const DropdownMenuItem<String?>(value: null, child: Text('直连')),
                                ..._tunnels.map((t) => DropdownMenuItem<String?>(value: t.file, child: Text(t.name))),
                              ],
                              onChanged: (val) {
                                setState(() => _formTunnel = val);
                              },
                            ),
                          ),
                        ],
                      ),

                      if (_formType == RuleType.wifiSsid) ...[
                        const SizedBox(height: 12),
                        Row(
                          children: [
                            Expanded(
                              child: TextField(
                                controller: _ssidController,
                                decoration: InputDecoration(
                                  labelText: 'WiFi SSID',
                                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                                  contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                                ),
                              ),
                            ),
                            const SizedBox(width: 8),
                            IconButton(
                              style: IconButton.styleFrom(
                                backgroundColor: AppTheme.background,
                                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                              ),
                              icon: const Icon(Icons.wifi_find, color: AppTheme.textSecondary),
                              onPressed: () async {
                                final granted = await SmartAgentApi.requestLocationPermission();
                                if (!granted) {
                                  if (!mounted) return;
                                  Toast.showFailure(context, '请先授予位置信息权限并开启定位');
                                  return;
                                }
                                final ssids = await SmartAgentApi.getSavedSsids();
                                if (!mounted) return;
                                if (ssids.isEmpty) {
                                  Toast.showFailure(context, '未获取到已保存的WiFi');
                                  return;
                                }
                                final selected = await showModalBottomSheet<String>(
                                  context: context,
                                  backgroundColor: Colors.white,
                                  builder: (ctx) {
                                    return ListView.separated(
                                      padding: const EdgeInsets.all(16),
                                      itemBuilder: (_, i) => ListTile(
                                        title: Text(ssids[i], style: GoogleFonts.inter(fontWeight: FontWeight.w600)),
                                        onTap: () => Navigator.pop(ctx, ssids[i]),
                                      ),
                                      separatorBuilder: (_, __) => const Divider(height: 1),
                                      itemCount: ssids.length,
                                    );
                                  },
                                );
                                if (selected != null) {
                                  _ssidController.text = selected;
                                }
                              },
                            ),
                          ],
                        ),
                      ],

                      const SizedBox(height: 16),

                      Row(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          TextButton(
                            onPressed: _cancelEdit,
                            style: TextButton.styleFrom(
                              foregroundColor: AppTheme.textSecondary,
                            ),
                            child: const Text('取消'),
                          ),
                          const SizedBox(width: 8),
                          ElevatedButton(
                            onPressed: _submitRule,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: AppTheme.vultrBlue,
                              foregroundColor: Colors.white,
                              elevation: 0,
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                            ),
                            child: const Text('保存'),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),
                    ],
                  ),
                )
                    : const SizedBox(width: double.infinity),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildAddButton() {
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Material(
          color: Colors.white,
          child: ListTile(
            onTap: _startNewRule,
            contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            leading: Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppTheme.background,
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Icon(Icons.playlist_add, color: AppTheme.textSecondary, size: 24),
            ),
            title: Text(
              'Add Tunnel Rule',
              style: GoogleFonts.inter(
                fontWeight: FontWeight.w700,
                fontSize: 15,
                color: AppTheme.textPrimary,
              ),
            ),
            subtitle: Text(
              '新增隧道匹配规则',
              style: GoogleFonts.inter(
                fontWeight: FontWeight.w500,
                fontSize: 13,
                color: AppTheme.textSecondary,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
