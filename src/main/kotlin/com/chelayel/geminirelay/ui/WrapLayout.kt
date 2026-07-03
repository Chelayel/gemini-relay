package com.chelayel.geminirelay.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * A [FlowLayout] that actually wraps: it reports a preferred height that
 * accounts for components flowing onto multiple rows at the container's
 * current width. Based on the well-known WrapLayout by Rob Camick.
 */
class WrapLayout(align: Int, hgap: Int, vgap: Int) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, false).also { it.width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            // During the first layout pass (and transiently while resizing) the
            // target's own width is still 0. Falling back to MAX_VALUE here would
            // lay every component out on a single row, reporting a huge width and
            // a too-short height — so the composer/tool-window height "jumps" once
            // a real width arrives. Walk up to the first ancestor that already has
            // a width and wrap against that instead.
            var container: Container = target
            while (container.size.width == 0 && container.parent != null) {
                container = container.parent
            }
            val targetWidth = if (container.size.width > 0) container.size.width else Int.MAX_VALUE
            val insets = target.insets
            val maxWidth = targetWidth - (insets.left + insets.right + hgap * 2)

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0
            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    addRow(dim, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += hgap
                rowWidth += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
            addRow(dim, rowWidth, rowHeight)

            dim.width += insets.left + insets.right + hgap * 2
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }

    private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) dim.height += vgap
        dim.height += rowHeight
    }
}
