package avoidingOversearch.autoML;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import hasco.core.Util;
import hasco.model.Component;
import hasco.model.ComponentInstance;
import hasco.model.NumericParameterDomain;
import hasco.model.Parameter;
import jaicore.planning.graphgenerators.task.tfd.TFDNode;
import jaicore.search.algorithms.standard.uncertainty.ISolutionDistanceMetric;

public class PipelineMetric implements ISolutionDistanceMetric<TFDNode> {

	private Collection<Component> components;

	public PipelineMetric(Collection<Component> components) {
		this.components = components;
	}

	@Override
	public double calculateSolutionDistance(List<TFDNode> solution1, List<TFDNode> solution2) {
		List<Component> components1 = null;
		List<Component> components2 = null;
		ComponentInstance componentInstance1 = null;
		ComponentInstance componentInstance2 = null;
		try {
			if (solution1 != null && !solution1.isEmpty()) {
				componentInstance1 = Util.getSolutionCompositionFromState(components,
						solution1.get(solution1.size() - 1).getState());
				components1 = Util.getComponentsOfComposition(componentInstance1);
				if (solution2 != null && !solution2.isEmpty()) {
					componentInstance2 = Util.getSolutionCompositionFromState(components,
							solution2.get(solution2.size() - 1).getState());
					components2 = Util.getComponentsOfComposition(componentInstance2);
				}
			}
		} catch (Exception e) {
			return Double.MAX_VALUE;
		}
		if (componentInstance1 != null && componentInstance2 != null && components1 != null && components2 != null
				&& !components1.isEmpty() && !components2.isEmpty()) {
			Component model1 = null, model2 = null, pp1 = null, pp2 = null;
			for (Component component : components1) {
				if (component.getProvidedInterfaces().contains("AbstractPreprocessor")) {
					pp1 = component;
				} else if (component.getProvidedInterfaces().contains("AbstractClassifier")) {
					model1 = component;
				}
			}
			for (Component component : components2) {
				if (component.getProvidedInterfaces().contains("AbstractPreprocessor")) {
					pp2 = component;
				} else if (component.getProvidedInterfaces().contains("AbstractClassifier")) {
					model2 = component;
				}
			}
			if (model1 != null && model2 != null && pp1 != null && pp2 != null) {
				boolean sameModel = model1.getName().equals(model2.getName());
				boolean samePP = pp1.getName().equals(pp2.getName());
				if (sameModel && samePP) {
					List<Double> numeric1 = new ArrayList<>();
					List<Double> numeric2 = new ArrayList<>();
					Set<String> categorical1 = new HashSet<>();
					Set<String> categorical2 = new HashSet<>();
					List<Parameter> pm1 = model1.getParameters().getLinearization();
					List<Parameter> pm2 = model2.getParameters().getLinearization();
					List<Parameter> ppp1 = pp1.getParameters().getLinearization();
					List<Parameter> ppp2 = pp2.getParameters().getLinearization();
					Map<String, String> params1 = componentInstance1.getParameterValues();
					Map<String, String> params2 = componentInstance2.getParameterValues();
					if (params1.isEmpty() || params2.isEmpty()) {
						return 1.0d;
					}

					for (Parameter p : pm1) {
						if (p.isNumeric()) {
							String value = params1.get(p.getName());
							try {
								Double d = Double.parseDouble(value);
								numeric1.add((d - ((NumericParameterDomain) p.getDefaultDomain()).getMin())
										/ (((NumericParameterDomain) p.getDefaultDomain()).getMax()
												- ((NumericParameterDomain) p.getDefaultDomain()).getMin()));
							} catch (Exception e) {
								numeric1.add(0.0d);
							}
						} else if (p.isCategorical()) {
							categorical1.add(p.getName() + "=" + params1.get(p.getName()));
						}
					}
					for (Parameter p : ppp1) {
						if (p.isNumeric()) {
							String value = params1.get(p.getName());
							try {
								Double d = Double.parseDouble(value);
								numeric1.add((d - ((NumericParameterDomain) p.getDefaultDomain()).getMin())
										/ (((NumericParameterDomain) p.getDefaultDomain()).getMax()
												- ((NumericParameterDomain) p.getDefaultDomain()).getMin()));
							} catch (Exception e) {
								numeric1.add(0.0d);
							}
						} else if (p.isCategorical()) {
							categorical1.add(p.getName() + "=" + params1.get(p.getName()));
						}
					}
					for (Parameter p : pm2) {
						if (p.isNumeric()) {
							String value = params2.get(p.getName());
							try {
								Double d = Double.parseDouble(value);
								numeric2.add((d - ((NumericParameterDomain) p.getDefaultDomain()).getMin())
										/ (((NumericParameterDomain) p.getDefaultDomain()).getMax()
												- ((NumericParameterDomain) p.getDefaultDomain()).getMin()));
							} catch (Exception e) {
								numeric2.add(0.0d);
							}
						} else if (p.isCategorical()) {
							categorical2.add(p.getName() + "=" + params2.get(p.getName()));
						}
					}
					for (Parameter p : ppp2) {
						if (p.isNumeric()) {
							String value = params2.get(p.getName());
							try {
								Double d = Double.parseDouble(value);
								numeric2.add((d - ((NumericParameterDomain) p.getDefaultDomain()).getMin())
										/ (((NumericParameterDomain) p.getDefaultDomain()).getMax()
												- ((NumericParameterDomain) p.getDefaultDomain()).getMin()));
							} catch (Exception e) {
								numeric2.add(0.0d);
							}
						} else if (p.isCategorical()) {
							categorical2.add(p.getName() + "=" + params2.get(p.getName()));
						}
					}

					Double numericDistance = 0.0d;
					for (int i = 0; i < Math.min(numeric1.size(), numeric2.size()); i++) {
						numericDistance += Math.pow(numeric1.get(i) - numeric2.get(i), 2);
					}
					numericDistance = Math.sqrt(numericDistance);

					Set<String> commonSet = new HashSet<>();
					commonSet.addAll(categorical1);
					commonSet.addAll(categorical2);
					double intersectionSize = 0.0d;
					for (String s : categorical1) {
						if (categorical2.contains(s)) {
							intersectionSize++;
						}
					}
					Double categoricalDistance = (commonSet.size() - intersectionSize) / commonSet.size();

					return numericDistance + categoricalDistance;
				} else if (sameModel && !samePP) {
					return 2;
				} else if (!sameModel && samePP) {
					return 3;
				} else {
					return 5;
				}
			}
		}
		return Double.MAX_VALUE;
	}

}
