package org.jetbrains.plugins.scala.lang.refactoring.introduceVariable;

/**
 * User: Alexander Podkhalyuzin
 * Date: 24.06.2008
 */
public interface ScalaIntroduceVariableDialogInterface {
  public boolean isOK();
  public ScalaIntroduceVariableSettings getSettings();
  public void show();
}
