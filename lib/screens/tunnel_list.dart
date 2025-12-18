import 'dart:async';
import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../api.dart';
import '../models.dart';
import '../theme.dart';
import '../widgets/toast.dart';
import 'tunnel_edit.dart';

class TunnelListScreen extends StatefulWidget {
  const TunnelListScreen({super.key});

  @override
  State<TunnelListScreen> createState() => _TunnelListScreenState();
}

class _TunnelListScreenState extends State<TunnelListScreen> with SingleTickerProviderStateMixin {
  List<LocalTunnel> _tunnels = [];
  bool _loading = true;
  VpnState? _currentState;
  StreamSubscription<VpnState>? _vpnSub;
  late AnimationController _fabController;
  late Animation<double> _expandAnimation;

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

  @override
  void initState() {
    super.initState();
    _fabController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 200),
      reverseDuration: const Duration(milliseconds: 200),
    );
    _expandAnimation = CurvedAnimation(
      parent: _fabController,
      curve: Curves.easeOutBack,
      reverseCurve: Curves.easeIn, // Changed from easeInBack to easeIn for immediate start
    );
    _loadTunnels();
    _initVpnState();
  }

  @override
  void dispose() {
    _vpnSub?.cancel();
    _fabController.dispose();
    super.dispose();
  }

  void _toggleFab() {
    if (_fabController.isDismissed) {
      _fabController.forward();
    } else {
      _fabController.reverse();
    }
  }

  Future<void> _loadTunnels() async {
    try {
      final tunnels = await SmartAgentApi.getTunnels();
      setState(() {
        _tunnels = tunnels;
        _loading = false;
      });
    } catch (e) {
      setState(() => _loading = false);
      // Handle error
    }
  }

  Future<void> _initVpnState() async {
    _vpnSub = SmartAgentApi.vpnStateStream.listen((state) {
      setState(() {
        _currentState = state;
      });
    });
    final initial = await SmartAgentApi.getVpnState();
    if (mounted) {
      setState(() => _currentState = initial);
    }
  }

  @override
  Widget build(BuildContext context) {
    final vpnState = _currentState ?? VpnState(isRunning: false);
    return Scaffold(
      body: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () {
          if (_fabController.isCompleted) {
            _fabController.reverse();
          }
        },
        child: RefreshIndicator(
          onRefresh: _loadTunnels,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _buildStatusCard(vpnState),
              const SizedBox(height: 24),
              Text(
                'AVAILABLE TUNNELS',
                style: GoogleFonts.inter(
                  color: AppTheme.textSecondary,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 1.0,
                ),
              ),
              const SizedBox(height: 12),
              if (_loading)
                const Center(child: CircularProgressIndicator())
              else if (_tunnels.isEmpty)
                _buildEmptyState()
              else
                ..._tunnels.map((t) => _buildTunnelItem(t, vpnState)),
            ],
          ),
        ),
      ),
      floatingActionButton: _buildFab(),
    );
  }

  Widget _buildFab() {
    // 容器高度设为 64 足够了，宽度适当留余量防止点击被裁剪
    return SizedBox(
      width: 360,
      height: 64,
      child: Stack(
        // 关键点1：全局右对齐 + 垂直居中
        // 这样里面的所有元素默认都会在垂直方向的中间，不用去算 bottom 是多少
        alignment: Alignment.centerRight,
        clipBehavior: Clip.none,
        children: [
          // 1. 导入配置 (Import)
          _buildFabOption(
            index: 2,
            icon: Icons.cloud_upload_outlined,
            label: '导入配置',
            onPressed: () async {
              _toggleFab();
              final success = await SmartAgentApi.importConfig();
              if (success) {
                _loadTunnels();
              }
            },
          ),

          // 2. 新建隧道 (New)
          _buildFabOption(
            index: 1,
            icon: Icons.add_circle_outline,
            label: '新建隧道',
            onPressed: () async {
              _toggleFab();
              // ... 这里保持你的原有逻辑 ...
              final fileName = 'manual_${DateTime.now().millisecondsSinceEpoch}.conf';
              final template = '''[Interface]\nPrivateKey =\nAddress = 10.0.0.2/32\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey =\nPresharedKey =\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = example.com:51820\nPersistentKeepalive = 25''';
              final saved = await Navigator.of(context).push<bool>(
                MaterialPageRoute(
                  builder: (_) => TunnelEditPage(
                    tunnel: LocalTunnel(name: 'NewTunnel', file: fileName),
                    initialContent: template,
                  ),
                ),
              );
              if (saved == true) _loadTunnels();
            },
          ),

          // 3. 主按钮 (Main Toggle)
          // 使用 Align 确保它稳稳地在最右侧垂直居中
          Align(
            alignment: Alignment.centerRight,
            child: Container(
              width: 50, // 调整为 50，比标准的 56 更精致，符合 Web 风格
              height: 50,
              decoration: _sharedButtonDecoration(shape: BoxShape.circle), // 统一风格
              child: Material(
                color: Colors.transparent,
                shape: const CircleBorder(),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: _toggleFab,
                  child: AnimatedBuilder(
                    animation: _fabController,
                    builder: (context, child) {
                      return Transform.rotate(
                        angle: _fabController.value * 0.75 * 3.14159,
                        child: Icon(
                          Icons.add,
                          color: AppTheme.vultrBlue,
                          size: 26,
                        ),
                      );
                    },
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFabOption({
    required int index,
    required IconData icon,
    required String label,
    required VoidCallback onPressed,
  }) {
    return AnimatedBuilder(
      animation: _expandAnimation,
      builder: (context, child) {
        final double value = _expandAnimation.value;

        // 关键点2：大幅减小间距，让它们靠得更近
        // 主按钮宽50 + 间距12 = 62.
        // Index 1 (New) 位于 62
        // Index 2 (Import) 位于 62 + 按钮1宽度(约110) + 间距10 = 约182
        final double endOffset = index == 1 ? 62.0 : 180.0;
        final double xTranslation = -endOffset * value;

        final double opacity = (value * 6).clamp(0.0, 1.0);
        // 减小缩放幅度的差异，让动画看起来更稳
        final double scale = 0.9 + (0.1 * value);

        // 关键点3：使用 Align 代替 Positioned
        // 配合 Stack 的 alignment: Alignment.centerRight，
        // 这里只需要处理水平位移，垂直方向自动绝对居中
        return Align(
          alignment: Alignment.centerRight,
          child: Transform.translate(
            offset: Offset(xTranslation, 0),
            child: Transform.scale(
              scale: scale,
              alignment: Alignment.centerRight,
              child: Opacity(
                opacity: opacity,
                child: Container(
                  height: 50, // 设定固定高度，比主按钮(50)小，层级分明
                  decoration: _sharedButtonDecoration(shape: BoxShape.rectangle), // 统一风格
                  child: Material(
                    color: Colors.transparent,
                    borderRadius: BorderRadius.circular(20),
                    clipBehavior: Clip.antiAlias,
                    child: InkWell(
                      onTap: onPressed,
                      splashColor: AppTheme.vultrBlue.withOpacity(0.1),
                      highlightColor: AppTheme.vultrBlue.withOpacity(0.05),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16), // 减小内边距
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(icon, color: AppTheme.vultrBlue, size: 18),
                            const SizedBox(width: 8),
                            Text(
                              label,
                              style: GoogleFonts.inter(
                                color: AppTheme.vultrBlue,
                                fontWeight: FontWeight.w600,
                                fontSize: 13,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  // 关键点4：提取公共样式，确保主按钮和小按钮的 阴影、边框、背景色 完全一致
  BoxDecoration _sharedButtonDecoration({required BoxShape shape}) {
    return BoxDecoration(
      color: Colors.white,
      shape: shape,
      borderRadius: shape == BoxShape.rectangle ? BorderRadius.circular(20) : null,
      border: Border.all(color: const Color(0xFFE6E9EF), width: 1), // 统一的极细灰边框
      boxShadow: [
        // 统一的淡蓝色弥散阴影
        BoxShadow(
          color: AppTheme.vultrBlue.withOpacity(0.15),
          blurRadius: 12,
          offset: const Offset(0, 4),
          spreadRadius: -2,
        ),
        // 统一的轮廓阴影
        BoxShadow(
          color: Colors.black.withOpacity(0.05),
          blurRadius: 2,
          offset: const Offset(0, 1),
        ),
      ],
    );
  }

  Widget _buildStatusCard(VpnState state) {
    final isRunning = state.isRunning;
    final color = isRunning ? const Color(0xFF10B981) : AppTheme.textSecondary;
    
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 22),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(8),
        boxShadow: AppTheme.cardShadow,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 10,
                height: 10,
                decoration: BoxDecoration(
                  color: color,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 10),
              Text(
                isRunning ? 'CONNECTED' : 'DISCONNECTED',
                style: GoogleFonts.inter(
                  color: color,
                  fontWeight: FontWeight.w700,
                  fontSize: 14,
                ),
              ),
              const Spacer(),
              Text(
                state.lastHandshake?.isNotEmpty == true ? state.lastHandshake! : '--',
                style: GoogleFonts.inter(
                  color: AppTheme.textSecondary,
                  fontSize: 12,
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          const Divider(height: 1, color: AppTheme.divider),
          const SizedBox(height: 20),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              _buildStatItem('TUNNEL', isRunning ? (state.tunnelName ?? 'Unknown') : '-'),
              _buildStatItem('UPLOAD', isRunning ? _formatBytes(state.txBytes) : '-'),
              _buildStatItem('DOWNLOAD', isRunning ? _formatBytes(state.rxBytes) : '-'),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildStatItem(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: GoogleFonts.inter(
            color: AppTheme.textSecondary,
            fontSize: 11,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          value,
          style: GoogleFonts.inter(
            color: AppTheme.textPrimary,
            fontSize: 15,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }

  Widget _buildTunnelItem(LocalTunnel tunnel, VpnState vpnState) {
    final isActive = vpnState.isRunning && vpnState.tunnelFile == tunnel.file;
    
    return Container(
        margin: const EdgeInsets.only(bottom: 12),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(8),
          boxShadow: AppTheme.cardShadow,
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: () async {
              if (isActive) {
                Toast.showFailure(context, '请先关闭隧道后再编辑配置');
                return;
              }
              final saved = await Navigator.of(context).push<bool>(
                MaterialPageRoute(
                  builder: (_) => TunnelEditPage(tunnel: tunnel),
                ),
              );
              if (saved == true) {
                _loadTunnels();
              }
            },
            onLongPress: () async {
              if (isActive) {
                Toast.showFailure(context, '正在使用的隧道无法删除');
                return;
              }
              final confirm = await showDialog<bool>(
                context: context,
                barrierColor: Colors.black54,
                builder: (ctx) => AlertDialog(
                  backgroundColor: Colors.white,
                  title: const Text('删除隧道'),
                  content: Text('确定删除 ${tunnel.name} ？'),
                  actions: [
                    TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
                    TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('删除')),
                  ],
                ),
              );
              if (confirm == true) {
                final ok = await SmartAgentApi.deleteTunnel(tunnel.file);
                if (ok) {
                  setState(() {
                    _tunnels.removeWhere((t) => t.file == tunnel.file);
                  });
                } else {
                  Toast.showFailure(context, '删除失败');
                }
              }
            },
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 22),
              child: Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: AppTheme.background,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Icon(Icons.vpn_lock, color: AppTheme.vultrBlue, size: 28),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          tunnel.name,
                          style: GoogleFonts.inter(
                            fontWeight: FontWeight.w600,
                            fontSize: 15,
                            color: AppTheme.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          tunnel.file,
                          style: GoogleFonts.inter(
                            fontSize: 12,
                            color: AppTheme.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  _buildToggleSwitch(
                    value: isActive,
                    onChanged: (val) {
                      SmartAgentApi.toggleTunnel(tunnel.file, val);
                    },
                  ),
                ],
              ),
            ),
          ),
        ),
      );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(40.0),
        child: Column(
          children: [
            Icon(Icons.layers_clear_outlined, size: 48, color: AppTheme.textSecondary),
            const SizedBox(height: 16),
            Text(
              'No Tunnels Found',
              style: GoogleFonts.inter(
                color: AppTheme.textSecondary,
                fontSize: 16,
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _formatBytes(int bytes) {
    const suffixes = ["B", "KB", "MB", "GB", "TB"];
    var i = 0;
    double d = bytes.toDouble();
    if (bytes <= 0) return "0 B";
    while ((d >= 1024) && (i < suffixes.length - 1)) {
      d /= 1024;
      i++;
    }
    return '${d.toStringAsFixed(2)} ${suffixes[i]}';
  }
}
