package net.prason.xaeronav.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import xaero.map.gui.dropdown.rightclick.RightClickOption;

/**
 * 右クリックメニューの項目を翻訳キーで見分ける。{@code getDisplayName()}は更新の止まった版のXaero
 * （1.21.6・1.21.7・1.21.9向けのWorld Map 1.39系）では{@code String}を返し、現行版の{@code Component}と
 * 記述子が違うので呼べない。キーを持つ{@code name}フィールドはどの版でも{@code String}のまま。
 */
@Mixin(RightClickOption.class)
public interface RightClickOptionAccessor {

    @Accessor(value = "name", remap = false)
    String xaeronav$translationKey();
}
