// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.yaml.schema;

import com.intellij.openapi.util.RecursionManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.TokenType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter;
import com.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter;
import com.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.YAMLTokenTypes;
import org.jetbrains.yaml.psi.*;

import java.util.Collection;
import java.util.Collections;

public class YamlPropertyAdapter implements JsonPropertyAdapter {

  private final YAMLKeyValue myProperty;

  public YamlPropertyAdapter(@NotNull YAMLKeyValue property) {myProperty = property;}

  @Nullable
  @Override
  public String getName() {
    return myProperty.getKeyText();
  }

  @Nullable
  @Override
  public JsonValueAdapter getNameValueAdapter() {
    return null; // todo: we need a separate adapter for names; but currently names schema is rarely used, let's just skip validation
  }

  @NotNull
  @Override
  public Collection<JsonValueAdapter> getValues() {
    YAMLValue value = myProperty.getValue();
    return value != null
           ? Collections.singletonList(createValueAdapterByType(value))
           : ContainerUtil.createMaybeSingletonList(createEmptyValueAdapter(myProperty, false));
  }

  @NotNull
  @Override
  public PsiElement getDelegate() {
    return myProperty;
  }

  @Nullable
  @Override
  public JsonObjectValueAdapter getParentObject() {
    return myProperty.getParentMapping() != null ? new YamlObjectAdapter(myProperty.getParentMapping()) : null;
  }

  @NotNull
  public static JsonValueAdapter createValueAdapterByType(@NotNull YAMLValue value) {
    if (value instanceof YAMLAlias) {
      PsiElement result = YamlObjectAdapter.resolveYamlAlias(value);
      if (result instanceof YAMLValue) {
        JsonValueAdapter adapter = RecursionManager.doPreventingRecursion(value, false, () -> createValueAdapterByType((YAMLValue)result));
        if (adapter != null) return adapter;
      }
    }
    if (value instanceof YAMLMapping) return new YamlObjectAdapter((YAMLMapping) value);
    if (value instanceof YAMLSequence) return new YamlArrayAdapter((YAMLSequence) value);
    return new YamlGenericValueAdapter(value);
  }

  @Nullable
  public static JsonValueAdapter createEmptyValueAdapter(@NotNull PsiElement context, boolean pinSelf) {
    if (context instanceof YAMLKeyValue && ((YAMLKeyValue)context).getValue() == null) {
      PsiElement next = PsiTreeUtil.skipWhitespacesForward(context);
      if (PsiUtilCore.getElementType(next) == YAMLTokenTypes.EOL) {
        next = PsiTreeUtil.skipWhitespacesForward(next);
        if (PsiUtilCore.getElementType(next) == YAMLTokenTypes.INDENT && !(PsiTreeUtil.skipWhitespacesForward(next) instanceof YAMLKeyValue)) {
          // potentially empty object after newline+indent
          return new YamlEmptyObjectAdapter(next);
        }
      }
    }
    PsiElement nextSibling = context.getNextSibling();
    PsiElement nodeToHighlight = PsiUtilCore.getElementType(nextSibling) == TokenType.WHITE_SPACE
                                 ? nextSibling
                                 : (pinSelf ? context : context.getLastChild());
    return nodeToHighlight == null ? null : new YamlEmptyValueAdapter(nodeToHighlight);
  }
}
