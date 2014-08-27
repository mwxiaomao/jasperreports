/*
 * JasperReports - Free Java Reporting Library.
 * Copyright (C) 2001 - 2014 TIBCO Software Inc. All rights reserved.
 * http://www.jaspersoft.com
 *
 * Unless you have purchased a commercial license agreement from Jaspersoft,
 * the following license terms apply:
 *
 * This program is part of JasperReports.
 *
 * JasperReports is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JasperReports is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with JasperReports. If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.jasperreports.engine.fill;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.jasperreports.data.cache.DataCacheHandler;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRDatasetParameter;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRExpression;
import net.sf.jasperreports.engine.JRExpressionCollector;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JRPrintElement;
import net.sf.jasperreports.engine.JRPrintPage;
import net.sf.jasperreports.engine.JRPrintRectangle;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JRRewindableDataSource;
import net.sf.jasperreports.engine.JRRuntimeException;
import net.sf.jasperreports.engine.JRScriptlet;
import net.sf.jasperreports.engine.JRSubreport;
import net.sf.jasperreports.engine.JRSubreportParameter;
import net.sf.jasperreports.engine.JRSubreportReturnValue;
import net.sf.jasperreports.engine.JRVariable;
import net.sf.jasperreports.engine.JRVisitor;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.ReportContext;
import net.sf.jasperreports.engine.base.JRVirtualPrintPage;
import net.sf.jasperreports.engine.design.JRValidationException;
import net.sf.jasperreports.engine.design.JRValidationFault;
import net.sf.jasperreports.engine.design.JRVerifier;
import net.sf.jasperreports.engine.type.ModeEnum;
import net.sf.jasperreports.engine.type.OverflowType;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.engine.util.JRSingletonCache;
import net.sf.jasperreports.engine.util.JRStyleResolver;
import net.sf.jasperreports.repo.RepositoryUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 * @author Teodor Danciu (teodord@users.sourceforge.net)
 * @version $Id$
 */
public class JRFillSubreport extends JRFillElement implements JRSubreport
{

	private static final Log log = LogFactory.getLog(JRFillSubreport.class);
	
	private static final JRSingletonCache<JRSubreportRunnerFactory> runnerFactoryCache = 
			new JRSingletonCache<JRSubreportRunnerFactory>(JRSubreportRunnerFactory.class);

	/**
	 *
	 */
	private Map<String, Object> parameterValues;
	private JRSubreportParameter[] parameters;
	private FillDatasetPosition datasetPosition;
	private boolean cacheIncluded;
	private Connection connection;
	private JRDataSource dataSource;
	private JasperReport jasperReport;
	private Object source;

	private Map<JasperReport,JREvaluator> loadedEvaluators;
	
	/**
	 * Values to be copied from the subreport.
	 */
	private FillReturnValues returnValues;

	private FillReturnValues.SourceContext returnValuesContext = new FillReturnValues.SourceContext()
	{
		@Override
		public JRVariable getVariable(String name)
		{
			return subreportFiller.getVariable(name);
		}

		@Override
		public Object getVariableValue(String name)
		{
			return subreportFiller.getVariableValue(name);
		}
	};
	
	/**
	 *
	 */
	protected JRBaseFiller subreportFiller;
	private JRPrintPage printPage;

	private JRSubreportRunner runner;
	
	/**
	 * Set of checked reports.
	 */
	private Set<JasperReport> checkedReports;


	/**
	 *
	 */
	protected JRFillSubreport(
		JRBaseFiller filler,
		JRSubreport subreport, 
		JRFillObjectFactory factory
		)
	{
		super(filler, subreport, factory);

		parameters = subreport.getParameters();
		returnValues = new FillReturnValues(subreport.getReturnValues(), factory, filler);
		
		loadedEvaluators = new HashMap<JasperReport,JREvaluator>();
		checkedReports = new HashSet<JasperReport>();
	}

	protected JRFillSubreport(JRFillSubreport subreport, JRFillCloneFactory factory)
	{
		super(subreport, factory);
		
		parameters = subreport.parameters;
		returnValues = new FillReturnValues(subreport.returnValues, factory);
		
		loadedEvaluators = new HashMap<JasperReport,JREvaluator>();// not sharing evaluators between clones
		checkedReports = subreport.checkedReports;
	}

	@Override
	protected void setBand(JRFillBand band)
	{
		super.setBand(band);
		
		returnValues.setBand(band);
	}


	/**
	 *
	 */
	public ModeEnum getModeValue()
	{
		return JRStyleResolver.getMode(this, ModeEnum.TRANSPARENT);
	}

	/**
	 * @deprecated Replaced by {@link #getUsingCache()}.
	 */
	public boolean isUsingCache()
	{
		return ((JRSubreport)parent).isUsingCache();
	}
		
	/**
	 *
	 */
	public boolean usingCache()
	{
		Boolean isUsingCache = getUsingCache();
		if (isUsingCache == null)
		{
			return source instanceof String;
		}
		return isUsingCache.booleanValue();
	}
		
	public Boolean isRunToBottom()
	{
		return ((JRSubreport) parent).isRunToBottom();
	}

	public void setRunToBottom(Boolean runToBottom)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public OverflowType getOverflowType()
	{
		return ((JRSubreport) parent).getOverflowType();
	}

	@Override
	public void setOverflowType(OverflowType overflowType)
	{
		throw new UnsupportedOperationException();
	}
		
	/**
	 *
	 */
	public JRExpression getParametersMapExpression()
	{
		return ((JRSubreport)parent).getParametersMapExpression();
	}

	/**
	 *
	 */
	public JRSubreportParameter[] getParameters()
	{
		return parameters;
	}

	/**
	 *
	 */
	public JRExpression getConnectionExpression()
	{
		return ((JRSubreport)parent).getConnectionExpression();
	}

	/**
	 *
	 */
	public JRExpression getDataSourceExpression()
	{
		return ((JRSubreport)parent).getDataSourceExpression();
	}

	/**
	 *
	 */
	public JRExpression getExpression()
	{
		return ((JRSubreport)parent).getExpression();
	}

	/**
	 *
	 */
	protected JRTemplateRectangle getJRTemplateRectangle()
	{
		return (JRTemplateRectangle) getElementTemplate();
	}


	protected JRTemplateElement createElementTemplate()
	{
		return new JRTemplateRectangle(getElementOrigin(), 
				filler.getJasperPrint().getDefaultStyleProvider(), this);
	}


	/**
	 *
	 */
	protected Collection<JRPrintElement> getPrintElements()
	{
		Collection<JRPrintElement> printElements = null;
		
		if (printPage != null)
		{
			printElements = printPage.getElements();
			//FIXME lucianc immediately dispose the page if virtualized
		}
		
		return printElements;
	}

	public void subreportPageFilled()
	{
		if (printPage != null)
		{
			subreportFiller.subreportPageFilled(printPage);
		}
	}


	/**
	 *
	 */
	protected void evaluate(
		byte evaluation
		) throws JRException
	{
		reset();
		
		evaluatePrintWhenExpression(evaluation);

		if (isPrintWhenExpressionNull() || isPrintWhenTrue())
		{
			evaluateSubreport(evaluation);
		}
	}

	protected JasperReport evaluateReport(byte evaluation) throws JRException
	{
		JasperReport report = null;
		
		JRExpression expression = getExpression();
		source = evaluateExpression(expression, evaluation);
		if (source != null) // FIXME put some default broken image like in browsers
		{
			Boolean isUsingCache = getUsingCache();
			if (isUsingCache == null)
			{
				isUsingCache = source instanceof String;
			}
			
			if (isUsingCache && filler.fillContext.hasLoadedSubreport(source))
			{
				report = filler.fillContext.getLoadedSubreport(source);
			}
			else
			{
				if (source instanceof net.sf.jasperreports.engine.JasperReport)
				{
					report = (JasperReport)source;
				}
				else if (source instanceof java.io.InputStream)
				{
					report = (JasperReport)JRLoader.loadObject((InputStream)source);
				}
				else if (source instanceof java.net.URL)
				{
					report = (JasperReport)JRLoader.loadObject((URL)source);
				}
				else if (source instanceof java.io.File)
				{
					report = (JasperReport)JRLoader.loadObject((File)source);
				}
				else if (source instanceof java.lang.String)
				{
					report = RepositoryUtil.getInstance(filler.getJasperReportsContext()).getReport(filler.getFillContext().getReportContext(), (String)source);
//						(JasperReport)JRLoader.loadObjectFromLocation(
//							(String)source, 
//							filler.reportClassLoader,
//							filler.urlHandlerFactory,
//							filler.fileResolver
//							);
				}
				else
				{
					throw new JRRuntimeException("Unknown subreport source class " + source.getClass().getName());
				}
				
				if (isUsingCache)
				{
					filler.fillContext.registerLoadedSubreport(source, report);
				}
			}
		}
		
		return report;
	}

	/**
	 *
	 */
	protected void evaluateSubreport(
		byte evaluation
		) throws JRException
	{
		evaluateProperties(evaluation);
		evaluateStyle(evaluation);

		jasperReport = evaluateReport(evaluation);
		
		if (jasperReport != null)
		{
			JRFillDataset parentDataset = expressionEvaluator.getFillDataset();
			datasetPosition = new FillDatasetPosition(parentDataset.fillPosition);
			datasetPosition.addAttribute("subreportUUID", getUUID());
			parentDataset.setCacheRecordIndex(datasetPosition, evaluation);
			
			/*   */
			connection = (Connection) evaluateExpression(
					getConnectionExpression(), evaluation);
	
			String cacheIncludedProp = JRPropertiesUtil.getOwnProperty(this, DataCacheHandler.PROPERTY_INCLUDED); 
			cacheIncluded = JRPropertiesUtil.asBoolean(cacheIncludedProp, true);// default to true

			if (filler.fillContext.hasDataSnapshot() && cacheIncluded)
			{
				// TODO lucianc put something here so that data adapters know not to create a data source
				dataSource = null;
			}
			else
			{
				dataSource = (JRDataSource) evaluateExpression(
						getDataSourceExpression(), evaluation);
			}
			
			parameterValues = 
				evaluateParameterValues(evaluation);

			if (subreportFiller != null)
			{
				filler.unregisterSubfiller(subreportFiller);
			}

			/*   */
			DatasetExpressionEvaluator evaluator = loadReportEvaluator();
			initSubreportFiller(evaluator);
			
			validateReport();
			
			returnValues.saveReturnVariables();
		}
	}

	protected Map<String, Object> evaluateParameterValues(byte evaluation) throws JRException
	{
		return getParameterValues(
			filler, 
			expressionEvaluator,
			getParametersMapExpression(), 
			getParameters(), 
			evaluation, 
			false, 
			jasperReport.getResourceBundle() != null,//hasResourceBundle 
			jasperReport.getFormatFactoryClass() != null//hasFormatFactory
			);
	}

	protected DatasetExpressionEvaluator loadReportEvaluator() throws JRException
	{
		DatasetExpressionEvaluator evaluator = null;
		boolean usingCache = usingCache();
		if (usingCache)
		{
			evaluator = loadedEvaluators.get(jasperReport);
		}
		if (evaluator == null)
		{
			evaluator = createEvaluator();
			if (usingCache)
			{
				loadedEvaluators.put(jasperReport, (JREvaluator)evaluator);
			}
		}
		return evaluator;
	}


	protected DatasetExpressionEvaluator createEvaluator() throws JRException
	{
		return JasperCompileManager.getInstance(filler.getJasperReportsContext()).getEvaluator(jasperReport);
	}


	protected void initSubreportFiller(DatasetExpressionEvaluator evaluator) throws JRException
	{
		if (log.isDebugEnabled())
		{
			log.debug("Fill " + filler.fillerId + ": creating subreport filler");
		}
		
		switch (jasperReport.getPrintOrderValue())
		{
			case HORIZONTAL :
			{
				subreportFiller = new JRHorizontalFiller(filler.getJasperReportsContext(), jasperReport, evaluator, this);
				break;
			}
			case VERTICAL :
			{
				subreportFiller = new JRVerticalFiller(filler.getJasperReportsContext(), jasperReport, evaluator, this);
				break;
			}
			default :
			{
				throw new JRRuntimeException("Unkown print order " + jasperReport.getPrintOrderValue().getValue() + ".");
			}
		}
		
		runner = getRunnerFactory().createSubreportRunner(this, subreportFiller);
		subreportFiller.setSubreportRunner(runner);
		
		subreportFiller.mainDataset.setFillPosition(datasetPosition);
		subreportFiller.mainDataset.setCacheSkipped(!cacheIncluded);
	}

	/**
	 * Utility method used for constructing a parameter values map for subreports, sub datasets and crosstabs.
	 * 
	 * @param filler report filler
	 * @param parametersMapExpression expression that yields bulk parameter values map
	 * @param subreportParameters list of individual parameter values
	 * @param evaluation evaluation type
	 * @param ignoreNullExpressions whether to ignore individual parameter value expressions
	 * @param removeResourceBundle whether to remove the {@link JRParameter#REPORT_RESOURCE_BUNDLE REPORT_RESOURCE_BUNDLE}
	 * 	value from the bulk values map
	 * @return the parameter values map
	 * @throws JRException
	 */
	public static Map<String, Object> getParameterValues(
			JRBaseFiller filler, 
			JRExpression parametersMapExpression, 
			JRDatasetParameter[] subreportParameters, 
			byte evaluation, 
			boolean ignoreNullExpressions, 
			boolean removeResourceBundle,
			boolean removeFormatFactory
			) throws JRException
	{
		return getParameterValues(filler, filler.getExpressionEvaluator(), 
				parametersMapExpression, subreportParameters, evaluation, 
				ignoreNullExpressions, removeResourceBundle, removeFormatFactory);
	}

	/**
	 * Utility method used for constructing a parameter values map for subreports, sub datasets and crosstabs.
	 * 
	 * @param filler report filler
	 * @param expressionEvaluator expression evaluator
	 * @param parametersMapExpression expression that yields bulk parameter values map
	 * @param subreportParameters list of individual parameter values
	 * @param evaluation evaluation type
	 * @param ignoreNullExpressions whether to ignore individual parameter value expressions
	 * @param removeResourceBundle whether to remove the {@link JRParameter#REPORT_RESOURCE_BUNDLE REPORT_RESOURCE_BUNDLE}
	 * 	value from the bulk values map
	 * @return the parameter values map
	 * @throws JRException
	 */
	public static Map<String, Object> getParameterValues(
			//TODO using the filler or current dataset?
			JRBaseFiller filler, 
			JRFillExpressionEvaluator expressionEvaluator,
			JRExpression parametersMapExpression, 
			JRDatasetParameter[] subreportParameters, 
			byte evaluation, 
			boolean ignoreNullExpressions, 
			boolean removeResourceBundle,
			boolean removeFormatFactory
			) throws JRException
	{
		Map<String, Object> parameterValues = null;
		if (parametersMapExpression != null)
		{
			parameterValues = (Map<String, Object>) expressionEvaluator.evaluate(parametersMapExpression, evaluation);
		}		
		
		if (parameterValues != null)
		{
			//if the expression evaluates to the master parameters map
			if (parameterValues == filler.getParameterValuesMap())
			{
				//create a clone of the map so that the master map is not altered
				parameterValues = new HashMap<String, Object>(parameterValues);
			}
			
			//parameterValues.remove(JRParameter.REPORT_LOCALE);
			if (removeResourceBundle)
			{
				parameterValues.remove(JRParameter.REPORT_RESOURCE_BUNDLE);
			}
			if (removeFormatFactory)
			{
				parameterValues.remove(JRParameter.REPORT_FORMAT_FACTORY);
			}
			//parameterValues.remove(JRParameter.REPORT_TIME_ZONE);
			parameterValues.remove(JRParameter.JASPER_REPORTS_CONTEXT);//FIXMENOW this is probably not necessary. other too
			parameterValues.remove(JRParameter.JASPER_REPORT);
			parameterValues.remove(JRParameter.REPORT_CONNECTION);
			parameterValues.remove(JRParameter.REPORT_MAX_COUNT);
			parameterValues.remove(JRParameter.REPORT_DATA_SOURCE);
			parameterValues.remove(JRParameter.REPORT_SCRIPTLET);
			// should we give access to scriplet extensions so that they can remove their parameters here?
			// yes, but then you should also give them access to create built-in parameters... too much trouble.
			JRScriptlet[] scriptlets = filler.getJasperReport().getScriptlets();
			if (scriptlets != null)
			{
				for(int i = 0; i < scriptlets.length; i++)
				{
					parameterValues.remove(scriptlets[i].getName() 
							+ JRScriptlet.SCRIPTLET_PARAMETER_NAME_SUFFIX);
				}
			}
			parameterValues.remove(JRParameter.REPORT_VIRTUALIZER);
			//parameterValues.remove(JRParameter.REPORT_CLASS_LOADER);
			parameterValues.remove(JRParameter.IS_IGNORE_PAGINATION);
			parameterValues.remove(JRParameter.SORT_FIELDS);
			parameterValues.remove(JRParameter.FILTER);
			parameterValues.remove(JRParameter.REPORT_PARAMETERS_MAP);
		}
		
		if (parameterValues == null)
		{
			parameterValues = new HashMap<String, Object>();
		}
		
		/*   */
		if (subreportParameters != null && subreportParameters.length > 0)
		{
			Object parameterValue = null;
			for(int i = 0; i < subreportParameters.length; i++)
			{
				JRExpression expression = subreportParameters[i].getExpression();
				if (expression != null || !ignoreNullExpressions)
				{
					parameterValue = expressionEvaluator.evaluate(expression, evaluation);
					if (parameterValue == null)
					{
						parameterValues.remove(subreportParameters[i].getName());
					}
					else
					{
						parameterValues.put(subreportParameters[i].getName(), parameterValue);
					}
				}
			}
		}
		
		if (!parameterValues.containsKey(JRParameter.REPORT_LOCALE))
		{
			parameterValues.put(JRParameter.REPORT_LOCALE, filler.getLocale());
		}

		if (!parameterValues.containsKey(JRParameter.REPORT_TIME_ZONE))
		{
			parameterValues.put(JRParameter.REPORT_TIME_ZONE, filler.getTimeZone());
		}

		if (
			!parameterValues.containsKey(JRParameter.REPORT_FORMAT_FACTORY)
			&& !removeFormatFactory
			)
		{
			parameterValues.put(JRParameter.REPORT_FORMAT_FACTORY, filler.getFormatFactory());
		}

		if (!parameterValues.containsKey(JRParameter.REPORT_CONTEXT))
		{
			ReportContext context = (ReportContext) filler.getMainDataset().getParameterValue(
					JRParameter.REPORT_CONTEXT, true);
			if (context != null)
			{
				parameterValues.put(JRParameter.REPORT_CONTEXT, context);
			}
		}
		
		return parameterValues;
	}

	protected void fillSubreport() throws JRException
	{
		if (getConnectionExpression() != null)
		{
			subreportFiller.fill(parameterValues, connection);
		}
		else if (getDataSourceExpression() != null)
		{
			subreportFiller.fill(parameterValues, dataSource);
		}
		else
		{
			subreportFiller.fill(parameterValues);
		}
	}
	

	/**
	 *
	 */
	protected boolean prepare(
		int availableHeight,
		boolean isOverflow
		) throws JRException
	{
		boolean willOverflow = false;

		super.prepare(availableHeight, isOverflow);
		
		if (subreportFiller == null)
		{
			setToPrint(false);
		}

		if (!isToPrint())
		{
			return willOverflow;
		}

		int elementHeight = getHeight();
		if (availableHeight < getRelativeY() + elementHeight)
		{
			setToPrint(false);
			return true;//willOverflow;
		}
			
		//willOverflow = prepareTextField((JRFillTextField)fillElement, availableStretchHeight);
		
		//subreportFiller.setPageHeight(getHeight() + availableStretchHeight);
		
		boolean filling = runner.isFilling();
		boolean toPrint = !isOverflow || isPrintWhenDetailOverflows() || !isAlreadyPrinted();
		boolean reprinted = isOverflow && isPrintWhenDetailOverflows();

		// for zero height subreports, check if we are at the bottom of the available space
		// and if the container is already marked to overflow.  in that case, do not
		// start the subreport here as the column header infinite loop test could throw
		// a false positive.
		if (elementHeight == 0 && availableHeight == getRelativeY()
				// test whether the report is starting now
				&& !filling && toPrint
				&& fillContainerContext != null// not sure if we need this
				&& fillContainerContext.isCurrentOverflow()
				&& fillContainerContext.isCurrentOverflowAllowed())
		{
			if (log.isDebugEnabled())
			{
				log.debug("zero height subreport at the bottom, not starting");
			}
			
			setToPrint(false);
			return true;//willOverflow;
		}
		
		if (!filling && toPrint && reprinted)
		{
			rewind();
		}
		
		if (printPage instanceof JRVirtualPrintPage)
		{
			// if the previous page was virtualized, dispose it as soon as possible.
			// this normally already happened when we added the elements to the master page,
			// but there are cases (e.g. overflow) when a page is not added to the master.
			((JRVirtualPrintPage) printPage).dispose();
		}
		
		int pageHeight;
		OverflowType overflowType = getOverflowType();
		if (overflowType == OverflowType.NO_STRETCH && !filler.getFillContext().isIgnorePagination())
		{
			// not allowed to stretch beyond the element height
			// note that we always have elementHeight <= availableHeight - getRelativeY(), it's tested above
			pageHeight = elementHeight;
		}
		else
		{
			// stretching by default
			pageHeight = availableHeight - getRelativeY();
		}
		subreportFiller.setPageHeight(pageHeight);

		synchronized (subreportFiller)
		{
			JRSubreportRunResult result;
			if (filling)
			{
				if (log.isDebugEnabled())
				{
					log.debug("Fill " + filler.fillerId + ": resuming " + subreportFiller.fillerId);
				}

				result = runner.resume();
			}
			else if (toPrint)
			{
				setReprinted(reprinted);

				if (log.isDebugEnabled())
				{
					log.debug("Fill " + filler.fillerId + ": starting " + subreportFiller.fillerId);
				}

				result = runner.start();
			}
			else
			{
				printPage = null;
				setStretchHeight(getHeight());
				setToPrint(false);

				return willOverflow;
			}
			
			if (result.getException() != null)
			{
				Throwable error = result.getException();
				
				if (log.isErrorEnabled())
				{
					log.error("Fill " + filler.fillerId + ": exception", error);
				}
				
				if (error instanceof RuntimeException)
				{
					throw (RuntimeException) error;
				}

				throw new JRRuntimeException(error);
			}

			if (result.hasFinished())
			{
				if (log.isDebugEnabled())
				{
					log.debug("Fill " + filler.fillerId + ": subreport " + subreportFiller.fillerId + " finished");
				}
				
				returnValues.copyValues(returnValuesContext);
			}
			else
			{
				if (log.isDebugEnabled())
				{
					log.debug("Fill " + filler.fillerId + ": subreport " + subreportFiller.fillerId + " to continue");
				}
			}

			printPage = subreportFiller.getCurrentPage();
			setStretchHeight(result.hasFinished() ? subreportFiller.getCurrentPageStretchHeight() : pageHeight);

			//if the subreport fill thread has not finished, 
			// it means that the subreport will overflow on the next page
			willOverflow = !result.hasFinished();
			
			if (!willOverflow)
			{
				//the subreport fill thread has finished and the next time we shall create a new one
				runner.reset();
			}
		}// synchronized
		
		Collection<JRPrintElement> printElements = getPrintElements();
		if (
			(printElements == null || printElements.size() == 0) &&
			isRemoveLineWhenBlank() //FIXME if the line won't be removed, the background does not appear
			)
		{
			setToPrint(false);
		}

		return willOverflow;
	}


	/**
	 *
	 */
	public void rewind() throws JRException
	{
		if (subreportFiller == null)
		{
			return;
		}
		
		cancelSubreportFill();
		
		initSubreportFiller(null);//FIXME used cached evaluator

		if (getConnectionExpression() == null && dataSource != null)
		{
			if(dataSource instanceof JRRewindableDataSource)
			{
				((JRRewindableDataSource) dataSource).moveFirst();
			}
			else
			{
//				if (log.isWarnEnabled())
//					log.warn("The subreport is placed on a non-splitting band, but it does not have a rewindable data source.");
				throw new JRException("The subreport is placed on a non-splitting band, but it does not have a rewindable data source.");
			}
		}
	}


	protected void cancelSubreportFill() throws JRException
	{
		if (log.isDebugEnabled())
		{
			log.debug("Fill " + filler.fillerId + ": cancelling " + subreportFiller.fillerId);
		}
		
		// marking the subreport filler for interruption
		subreportFiller.setInterrupted(true);
		
		synchronized (subreportFiller)
		{
			// forcing the creation of a new thread and a new subreport filler
			runner.cancel();
			runner.reset();
		}

		filler.unregisterSubfiller(subreportFiller);
	}


	/**
	 *
	 */
	protected JRPrintElement fill()
	{
		//FIXME lucianc create a frame instead to avoid HTML layers
		JRPrintRectangle printRectangle = new JRTemplatePrintRectangle(getJRTemplateRectangle(), printElementOriginator);

		printRectangle.setUUID(getUUID());
		printRectangle.setX(getX());
		printRectangle.setY(getRelativeY());
		printRectangle.setWidth(getWidth());
		printRectangle.setHeight(getStretchHeight());
		
		return printRectangle;
	}


	/**
	 *
	 */
	public void collectExpressions(JRExpressionCollector collector)
	{
		collector.collect(this);
	}

	/**
	 *
	 */
	public void visit(JRVisitor visitor)
	{
		visitor.visitSubreport(this);
	}
	

	public JRSubreportReturnValue[] getReturnValues()
	{
		return ((JRSubreport) parent).getReturnValues();
	}

	protected void validateReport() throws JRException
	{
		if (!checkedReports.contains(jasperReport))
		{
			verifyBandHeights();
			returnValues.checkReturnValues(returnValuesContext);
			
			if (usingCache())
			{
				checkedReports.add(jasperReport);
			}
		}
	}
	
	protected void verifyBandHeights() throws JRException
	{
		if (!filler.fillContext.isIgnorePagination())
		{
			int pageHeight;
			int topMargin = jasperReport.getTopMargin();
			int bottomMargin = jasperReport.getBottomMargin();
			
			JRBaseFiller parentFiller = filler;
			do
			{
				// set every time, so at the end it will be the master page height
				pageHeight = parentFiller.jasperReport.getPageHeight();
				
				// sum parent page margins
				topMargin += parentFiller.jasperReport.getTopMargin();
				bottomMargin += parentFiller.jasperReport.getBottomMargin();
				
				parentFiller = parentFiller.parentFiller;
			}
			while (parentFiller != null);
			
			List<JRValidationFault> brokenRules = new ArrayList<JRValidationFault>();
			JRVerifier.verifyBandHeights(brokenRules, 
					jasperReport, pageHeight, topMargin, bottomMargin);
			
			if (!brokenRules.isEmpty())
			{
				throw new JRValidationException("Band height validation for subreport \""
						+ jasperReport.getName() + "\" failed in the current page context "
						+ "(height = " + pageHeight + ", top margin = " + topMargin
						+ ", bottom margin = " + bottomMargin + ") : ",
						brokenRules);
			}
			else if (log.isDebugEnabled())
			{
				log.debug("Band height validation for subreport \""
						+ jasperReport.getName() + "\" succeeded in the current page context "
						+ "(height = " + pageHeight + ", top margin = " + topMargin
						+ ", bottom margin = " + bottomMargin + ")");
			}
		}
	}
	
	
	protected void resolveElement (JRPrintElement element, byte evaluation)
	{
		// nothing
	}


	/**
	 * @deprecated Replaced by {@link #getUsingCache()}.
	 */
	public Boolean isOwnUsingCache()
	{
		return ((JRSubreport)parent).isOwnUsingCache();
	}


	public Boolean getUsingCache()
	{
		return ((JRSubreport)parent).getUsingCache();
	}


	public void setUsingCache(Boolean isUsingCache)
	{
	}


	public JRFillCloneable createClone(JRFillCloneFactory factory)
	{
		return new JRFillSubreport(this, factory);
	}
	
	protected JRSubreportRunnerFactory getRunnerFactory() throws JRException
	{
		String factoryClassName = filler.getPropertiesUtil().getProperty(JRSubreportRunnerFactory.SUBREPORT_RUNNER_FACTORY);
		if (factoryClassName == null)
		{
			throw new JRException("Property \"" + JRSubreportRunnerFactory.SUBREPORT_RUNNER_FACTORY + "\" must be set");
		}
		return runnerFactoryCache.getCachedInstance(factoryClassName);
	}

	protected int getContentsStretchHeight()
	{
		return subreportFiller.getCurrentPageStretchHeight();
	}
}
