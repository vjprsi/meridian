import 'package:flutter/material.dart';

import '../widgets.dart';
import 'cards.dart';
import 'home.dart';
import 'more.dart';
import 'transfer.dart';

/// Signed-in shell: bottom navigation between Home, Pay, Cards and More.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final pages = [
      HomeScreen(onMoveMoney: () => setState(() => _index = 1)),
      const TransferScreen(),
      const CardsScreen(),
      const MoreScreen(),
    ];
    return Scaffold(
      body: IndexedStack(index: _index, children: pages),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.home_outlined), selectedIcon: const Icon(Icons.home), label: l.navHome),
          NavigationDestination(icon: const Icon(Icons.swap_horiz), label: l.navPay),
          NavigationDestination(
              icon: const Icon(Icons.credit_card_outlined), selectedIcon: const Icon(Icons.credit_card), label: l.navCards),
          NavigationDestination(icon: const Icon(Icons.menu), label: l.navMore),
        ],
      ),
    );
  }
}
