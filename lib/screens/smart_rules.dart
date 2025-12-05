import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../api.dart';
import '../models.dart';
import '../theme.dart';
import 'app_select.dart';
import 'log_page.dart';
import '../widgets/notifier.dart';

class SmartRulesScreen extends StatefulWidget {
  const SmartRulesScreen({super.key});

  @override
  State<SmartRulesScreen> createState() => _SmartRulesScreenState();
}

class _SmartRulesScreenState extends State<SmartRulesScreen> {
  bool _enabled = false;
  bool _appRuleEnabled = false;
  List<String> _selectedApps = [];
  List<AgentRule> _rules = [];
  List<LocalTunnel> _tunnels = [];
  RuleType _formType = RuleType.wifiSsid;
  final TextEditingController _ssidController = TextEditingController();
  String? _formTunnel;
  String? _editingRuleId;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadConfig();
  }

  @override
  void dispose() {
    _ssidController.dispose();
    super.dispose();
  }

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
        return null; // No icon for inactive state
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

  Future<void> _loadConfig() async {
    try {
      final config = await SmartAgentApi.getSmartConfig();
      final tunnels = await SmartAgentApi.getTunnels();
      setState(() {
        _enabled = config['enabled'] as bool;
        _appRuleEnabled = config['appRuleEnabled'] as bool? ?? false;
        _selectedApps = (config['selectedApps'] as List?)?.cast<String>() ?? [];
        _rules = (config['rules'] as List)
            .map((e) => AgentRule.fromMap(Map<String, dynamic>.from(e)))
            .toList();
        _tunnels = tunnels;
        _formTunnel = _formTunnel ?? (_tunnels.isNotEmpty ? _tunnels.first.file : null);
        _loading = false;
      });
    } catch (e) {
      setState(() => _loading = false);
    }
  }

  Future<void> _saveAgentRuleConfig() async {
    await SmartAgentApi.setAgentRuleConfig(
      enabled: _enabled,
      rules: _rules,
    );
  }

  Future<void> _saveAppRuleConfig() async {
    await SmartAgentApi.setAppRuleConfig(
      appRuleEnabled: _appRuleEnabled,
      selectedApps: _selectedApps,
    );
  }

  void _startNewRule() {
    setState(() {
      _editingRuleId = null;
      _formType = RuleType.wifiSsid;
      _ssidController.text = '';
      _formTunnel = _tunnels.isNotEmpty ? _tunnels.first.file : null;
    });
  }

  void _startEdit(AgentRule rule) {
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

  void _submitRule() {
    if (_formType == RuleType.wifiSsid) {
      final ssid = _ssidController.text.trim();
      if (ssid.isEmpty) {
        Notifier.show(context, '请填写WiFi SSID');
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

    final rule = AgentRule(
      id: _editingRuleId ?? DateTime.now().millisecondsSinceEpoch.toString(),
      type: _formType,
      value: _formType == RuleType.wifiSsid ? _ssidController.text.trim() : null,
      tunnelFile: tunnelFile,
      tunnelName: tunnelName,
      enabled: true,
    );
    setState(() {
      final idx = _rules.indexWhere((r) => r.id == rule.id);
      if (idx != -1) {
        _rules[idx] = rule;
      } else {
        _rules.add(rule);
      }
      _editingRuleId = null;
      _ssidController.clear();
      _formType = RuleType.wifiSsid;
      _formTunnel = _tunnels.isNotEmpty ? _tunnels.first.file : null;
    });
    _saveAgentRuleConfig();
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Center(child: CircularProgressIndicator());

    return Scaffold(
      body: CustomScrollView(
        slivers: [
          SliverToBoxAdapter(child: _buildAppRuleCard()),
          SliverToBoxAdapter(child: _buildHeader()),
          if (_enabled)
            SliverToBoxAdapter(
              child: ReorderableListView.builder(
                physics: const NeverScrollableScrollPhysics(),
                shrinkWrap: true,
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                itemCount: _rules.length,
                buildDefaultDragHandles: false,
                proxyDecorator: (child, index, animation) => child,
                onReorder: (oldIndex, newIndex) {
                  if (oldIndex < newIndex) newIndex -= 1;
                  setState(() {
                    final item = _rules.removeAt(oldIndex);
                    _rules.insert(newIndex, item);
                  });
                  _saveAgentRuleConfig();
                },
                itemBuilder: (context, index) {
                  final rule = _rules[index];
                  return _buildRuleItem(rule, index);
                },
              ),
            ),
          if (_enabled)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 80),
                child: Column(
                  children: [
                    _buildInlineEditor(),
                    const SizedBox(height: 64),
                  ],
                ),
              ),
            ),
        ],
      ),
      floatingActionButton: null,
    );
  }

  Widget _buildAppRuleCard() {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 16, 16, 8), // Top: 16, Bottom: 8
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
                    Text('应用分流', style: GoogleFonts.inter(fontSize: 15, fontWeight: FontWeight.w700)),
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
                // Navigate immediately; AppSelectPage shows its own loader.
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
                side: const BorderSide(color: AppTheme.divider),
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

  Widget _buildHeader() {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
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
    return Container(
      key: ValueKey(rule.id),
      margin: const EdgeInsets.fromLTRB(0, 0, 0, 8),
      child: Material(
        color: Colors.white,
        borderRadius: BorderRadius.circular(8),
        child: ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
            leading: Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppTheme.background,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Icon(
                rule.type == RuleType.wifiSsid ? Icons.wifi : Icons.public,
                color: AppTheme.textSecondary,
                size: 28,
              ),
          ),
          title: Text(
            rule.type == RuleType.wifiSsid ? 'WiFi: ${rule.value}' : rule.type.label,
            style: GoogleFonts.inter(
              fontWeight: FontWeight.w600,
              fontSize: 15,
              color: AppTheme.textPrimary,
            ),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          subtitle: Text(
            'Target: ${rule.tunnelName}',
            style: GoogleFonts.inter(
              fontSize: 13,
              color: AppTheme.textSecondary,
            ),
          ),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              IconButton(
                icon: const Icon(Icons.edit_outlined, size: 20),
                color: AppTheme.textSecondary,
                onPressed: () => _startEdit(rule),
              ),
              ReorderableDragStartListener(
                index: index,
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTapDown: (_) {}, // Prevent parent tap/scroll during drag start
                  child: Padding(
                    padding: const EdgeInsets.all(8),
                    child: _buildDragHandle(),
                  ),
                ),
              ),
            ],
          ),
          onLongPress: () async {
            final confirm = await showDialog<bool>(
              context: context,
              barrierColor: Colors.black54,
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
      ),
    );
  }

  Widget _buildInlineEditor() {
    return Container(
      key: const ValueKey('inline-editor'),
      margin: const EdgeInsets.only(top: 6),
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
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                _editingRuleId == null ? '新增规则' : '编辑规则',
                style: GoogleFonts.inter(fontSize: 15, fontWeight: FontWeight.w700),
              ),
              TextButton(
                onPressed: _editingRuleId != null ? _startNewRule : null,
                child: Text(
                  _editingRuleId != null ? '新增' : '新增',
                  style: GoogleFonts.inter(fontWeight: FontWeight.w600, color: AppTheme.textSecondary),
                ),
              ),
            ],
          ),
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
                    border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
                    contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
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
                    border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
                    contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
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
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
                      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton(
                  icon: const Icon(Icons.wifi_find, color: AppTheme.textSecondary),
                  onPressed: () async {
                    final granted = await SmartAgentApi.requestLocationPermission();
                    if (!granted) {
                      if (!mounted) return;
                      Notifier.show(context, '请先授予位置信息权限并开启定位');
                      return;
                    }
                    final ssids = await SmartAgentApi.getSavedSsids();
                    if (!mounted) return;
                    if (ssids.isEmpty) {
                      Notifier.show(context, '未获取到已保存的WiFi');
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
          const SizedBox(height: 12),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              TextButton(
                onPressed: _startNewRule,
                child: Text('重置', style: GoogleFonts.inter(color: AppTheme.textSecondary)),
              ),
              const SizedBox(width: 12),
              ElevatedButton(
                onPressed: _submitRule,
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppTheme.vultrBlue,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                ),
                child: const Text('保存'),
              ),
            ],
          )
        ],
      ),
    );
  }
}
