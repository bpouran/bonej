

/**
 * Slice_Geometry plugin for ImageJ
 * Copyright 2009 Michael Doube 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.plugin.filter.PlugInFilter;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.gui.*;

import java.awt.Rectangle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.doube.bonej.Thickness_;
import org.doube.bonej.Rotating_Calipers;

/**
 * <p>Calculate 2D geometrical parameters</p>
 * 
 * @author Michael Doube
 *
 */

public class Slice_Geometry implements PlugInFilter {
    ImagePlus imp;
    protected ImageStack stack;
    public static final double PI = 3.141592653589793;
    private int boneID, al, startSlice, endSlice;
    private double vW, vH, vD, airHU, minBoneHU, maxBoneHU;
    private String units, analyse, calString;
    private boolean doThickness, doCentroids, doCopy, doOutline, doAxes, doStack, isCalibrated;
    private double[] cslice, cortArea, Sx, Sy, Sxx, Syy, Sxy, Myy, Mxx, Mxy, theta, 
    Imax, Imin, Ipm, R1, R2, maxRadMin, maxRadMax, Zmax, Zmin, ImaxFast, IminFast;
    private boolean[] emptySlices;
    private double[][] sliceCentroids;
    Calibration cal;

    public int setup(String arg, ImagePlus imp) {
	if (imp == null) {
	    IJ.noImage();
	    return DONE;
	}
	this.imp = imp;
	this.cal = imp.getCalibration();
	this.vW = this.cal.pixelWidth;
	this.vH = this.cal.pixelHeight;
	this.vD = this.cal.pixelDepth;
	this.stack = imp.getStack();
	this.al = this.stack.getSize()+1;
	//TODO properly support 8bit images
	return DOES_8G + DOES_16 + SUPPORTS_MASKING;
    }

    public void run(ImageProcessor ip) {
	//show a setup dialog and set calibration
	//	setHUCalibration();
	IJ.run("Threshold...");
	new WaitForUserDialog("Adjust the threshold, then click OK.").show();
	this.minBoneHU = (short)this.imp.getProcessor().getMinThreshold();
	this.maxBoneHU = (short)this.imp.getProcessor().getMaxThreshold();

	if (!showDialog()){
	    return;
	}

	if (calculateCentroids() == 0){
	    IJ.error("No pixels available to calculate.\n" +
	    "Please check the threshold and ROI.");
	    return;
	}

	calculateMoments();
	
	//TODO locate centroids of multiple sections in a single plane
	//TODO cortical thickness - local thickness methods?
	//TODO annotate results

	showSliceResults();

    }

    private double calculateCentroids(){
	//2D centroids
	this.sliceCentroids = new double[2][this.al]; 
	//pixel counters
	double cstack = 0;
	Rectangle r = this.stack.getRoi();
	int w = this.stack.getWidth();
	this.emptySlices = new boolean[this.al];
	this.cslice = new double[this.al];
	this.cortArea = new double[this.al];
	for (int s = this.startSlice; s <= this.endSlice; s++) {
	    double sumx = 0; double sumy = 0;
	    this.cslice[s] = 0;
	    short[] pixels = (short[])this.stack.getPixels(s);
	    for (int y=r.y; y<(r.y+r.height); y++) {
		int offset = y*w;
		for (int x=r.x; x<(r.x+r.width); x++) {
		    int i = offset + x;
		    if (pixels[i] >= this.minBoneHU && pixels[i] <= this.maxBoneHU){
			this.cslice[s]++;
			cortArea[s] += this.vW * this.vH;
			sumx += x * this.vW;
			sumy += y * this.vH;
		    }
		}
	    }
	    if (this.cslice[s] > 0){
		this.sliceCentroids[0][s] = sumx / this.cslice[s];
		this.sliceCentroids[1][s] = sumy / this.cslice[s];
		cstack += this.cslice[s];
		this.emptySlices[s] = false;
	    } else {
		this.emptySlices[s] = true;
	    }
	}
	return cstack;
    }

    private void calculateMoments(){
	Rectangle r = this.stack.getRoi();
	int w = this.stack.getWidth();
	//START OF Ix AND Iy CALCULATION 
	this.Sx = new double[this.al]; this.Sy = new double[this.al]; this.Sxx = new double[this.al]; this.Syy = new double[this.al]; this.Sxy = new double[this.al];
	this.Myy = new double[this.al]; this.Mxx = new double[this.al]; this.Mxy = new double[this.al]; this.theta = new double[this.al];
	for (int s = 1; s <= this.stack.getSize(); s++) {
	    if (!this.emptySlices[s]){
		short[] pixels = (short[])this.stack.getPixels(s);
		for (int y = r.y; y < (r.y + r.height); y++) {
		    int offset = y * w;
		    for (int x = r.x; x < (r.x+r.width); x++) {
			int i = offset + x;
			if (pixels[i] >= this.minBoneHU && pixels[i] <= this.maxBoneHU){
			    this.Sx[s] += x;			
			    this.Sy[s] += y;			
			    this.Sxx[s] += x*x;
			    this.Syy[s] += y*y;
			    this.Sxy[s] += y*x;
			}
		    }
		}
		this.Myy[s] = this.Sxx[s] - (this.Sx[s] * this.Sx[s] / this.cslice[s]) + this.cslice[s]/12; //this.cslice[]/12 is for each pixel's moment around its own centroid
		this.Mxx[s] = this.Syy[s] - (this.Sy[s] * this.Sy[s] / this.cslice[s]) + this.cslice[s]/12;
		this.Mxy[s] = this.Sxy[s] - (this.Sx[s] * this.Sy[s] / this.cslice[s]) + this.cslice[s]/12;
		if (this.Mxy[s] == 0) this.theta[s] = 0;
		else {
		    this.theta[s] = Math.atan((this.Mxx[s]-this.Myy[s]+Math.sqrt(Math.pow(this.Mxx[s]-this.Myy[s],2) + 4*this.Mxy[s]*this.Mxy[s]))/(2*this.Mxy[s]));
		    //thetaFast gives same result except jumps when hits PI/4 and -PI/4
		    //thetaFast[s] = Math.atan(2*Mxy[s]/(Myy[s]-Mxx[s])) / 2;
		}
	    }
	}
	//END OF Ix and Iy CALCULATION
	//START OF Imax AND Imin CALCULATION
	this.Imax = new double[this.al]; this.Imin = new double[this.al]; this.Ipm = new double[this.al]; 
	this.R1 = new double[this.al]; this.R2 = new double[this.al]; this.maxRadMin = new double[this.al]; this.maxRadMax = new double[this.al];
	this.Zmax = new double[this.al]; this.Zmin = new double[this.al]; this.ImaxFast = new double[this.al]; this.IminFast = new double[this.al]; 
	for (int s=1;s<=this.stack.getSize();s++) {
	    if (!this.emptySlices[s]){
		short[] pixels = (short[])this.stack.getPixels(s);
		this.Sx[s]=0; this.Sy[s]=0; this.Sxx[s]=0; this.Syy[s]=0; this.Sxy[s]=0;
		for (int y=r.y; y<(r.y+r.height); y++) {
		    int offset = y*w;
		    for (int x=r.x; x<(r.x+r.width); x++) {
			int i = offset + x;
			if (pixels[i] >= this.minBoneHU && pixels[i] <= this.maxBoneHU){
			    this.Sx[s] += x*Math.cos(this.theta[s]) + y*Math.sin(this.theta[s]);				//normal distance from parallel axis summed over pixels
			    this.Sy[s] += y*Math.cos(this.theta[s]) - x*Math.sin(this.theta[s]);
			    this.Sxx[s] += (x*Math.cos(this.theta[s]) + y*Math.sin(this.theta[s]))
			    * (x*Math.cos(this.theta[s]) + y*Math.sin(this.theta[s]));				//squared normal distances from parallel axis (Iz)
			    this.Syy[s] += (y*Math.cos(this.theta[s]) - x*Math.sin(this.theta[s]))
			    * (y*Math.cos(this.theta[s]) - x*Math.sin(this.theta[s]));
			    this.Sxy[s] += (y*Math.cos(theta[s]) - x*Math.sin(theta[s]))
			    *(x*Math.cos(theta[s])+y*Math.sin(theta[s]));          				
			    //maximum distance from minimum principal axis (longer)
			    this.maxRadMin[s] = Math.max(this.maxRadMin[s],Math.abs((x-this.sliceCentroids[0][s])*Math.cos(this.theta[s])
				    + (y-this.sliceCentroids[1][s])*Math.sin(theta[s])));
			    //maximum distance from maximum principal axis (shorter)
			    this.maxRadMax[s] = Math.max(this.maxRadMax[s],Math.abs((y-this.sliceCentroids[1][s])*Math.cos(this.theta[s]) - (x-sliceCentroids[0][s])*Math.sin(theta[s])));
			}
		    }
		}
		this.Imax[s] = this.Sxx[s] - (this.Sx[s] * this.Sx[s] / this.cslice[s]) + this.cslice[s]*(Math.pow(Math.cos(this.theta[s]),2)+Math.pow(Math.sin(this.theta[s]),2)) / 12;			//2nd moment of area around minimum principal axis (shorter axis, bigger I) 
		this.Imin[s] = this.Syy[s] - (this.Sy[s] * this.Sy[s] / this.cslice[s]) + this.cslice[s]*(Math.pow(Math.cos(this.theta[s]),2)+Math.pow(Math.sin(this.theta[s]),2)) / 12;			//2nd moment of area around maximum principal axis (longer axis, smaller I)
		this.Ipm[s] = this.Sxy[s] - (this.Sy[s] * this.Sx[s] / this.cslice[s]) + this.cslice[s]*(Math.pow(Math.cos(this.theta[s]),2)+Math.pow(Math.sin(this.theta[s]),2)) / 12;			//product moment of area, should be 0 if theta calculated perfectly
		this.R1[s] = Math.sqrt(this.Imin[s] / this.cslice[s]);    				//length of major axis
		this.R2[s] = Math.sqrt(this.Imax[s] / this.cslice[s]);					//length of minor axis
		this.Zmax[s] = this.Imax[s] / this.maxRadMin[s];							//Section modulus around maximum principal axis
		this.Zmin[s] = this.Imin[s] / this.maxRadMax[s];							//Section modulus around minimum principal axis
		this.ImaxFast[s] = (this.Mxx[s]+this.Myy[s])/2 + Math.sqrt(Math.pow(((this.Mxx[s]-this.Myy[s])/2),2)+this.Mxy[s]*this.Mxy[s]);
		this.IminFast[s] = (this.Mxx[s]+this.Myy[s])/2 - Math.sqrt(Math.pow(((this.Mxx[s]-this.Myy[s])/2),2)+this.Mxy[s]*this.Mxy[s]);
	    }
	}
    }

    //TODO fix this, it's a mess.
    /**
     * Set up threshold based on HU if image is calibrated or
     * user-based threshold if it is uncalibrated
     * 
     */
    private void setHUCalibration(){
	this.minBoneHU = 0;		//minimum bone value in HU 
	this.maxBoneHU = 4000;		//maximum bone value in HU
	this.vW = this.cal.pixelWidth;
	this.vH = this.cal.pixelHeight;
	this.vD = this.cal.pixelDepth;
	this.units = this.cal.getUnits();
	double[] coeff = this.cal.getCoefficients();
	if (!this.cal.calibrated() || this.cal == null || (this.cal.getCValue(0) == 0 && this.cal.getCoefficients()[1] == 1)){
	    this.isCalibrated = false;
	    this.calString = "Image is uncalibrated\nEnter air and bone pixel values";
	    ImageStatistics stats = imp.getStatistics();
	    if (stats.min < 50 && stats.min >= 0){
		this.airHU = 0;
	    }
	    else if (stats.min > 31000){
		this.airHU = 31768;
	    }
	    else if (stats.min < -800){
		this.airHU = -1000;
	    } else {
		this.airHU = 0;
	    }
	} else {
	    this.isCalibrated = true;
	    this.calString = "Image is calibrated\nEnter HU below:";
	    this.airHU = -1000;
	}
	this.minBoneHU = this.airHU + 1000;
	this.maxBoneHU = this.airHU + 5000;
	if (this.cal.calibrated()) {
	    //convert HU limits to pixel values
	    IJ.log("Image is calibrated, using "+this.minBoneHU+" and "+this.maxBoneHU+" HU as bone cutoffs");
	    this.minBoneHU = (short)Math.round(this.cal.getRawValue(this.minBoneHU));
	    this.maxBoneHU = (short)Math.round(this.cal.getRawValue(this.maxBoneHU));
	    IJ.log("Vox Width: "+vW+"; Vox Height: "+vH+" "+units);
	    IJ.log("Calibration coefficients:"+coeff[0]+","+coeff[1]);
	    IJ.log("this.minBoneHU = "+this.minBoneHU+", this.maxBoneHU = "+this.maxBoneHU);
	}
	else {
	    IJ.log("Image is not calibrated, using user-determined threshold");
	    IJ.run("Threshold...");
	    new WaitForUserDialog("This image is not density calibrated.\nSet the threshold, then click OK.").show();
	    this.minBoneHU = (short)this.stack.getProcessor(this.imp.getCurrentSlice()).getMinThreshold();
	    this.maxBoneHU = (short)this.stack.getProcessor(this.imp.getCurrentSlice()).getMaxThreshold();
	}
	return;
    }

    private boolean showDialog(){
	GenericDialog gd = new GenericDialog("Options");

	gd.addCheckbox("Cortical Thickness", true);
	gd.addCheckbox("Draw Axes", true);
	gd.addCheckbox("Draw Centroids", true);
	gd.addCheckbox("Draw Outline", false);
	gd.addCheckbox("Annotated Copy", true);
	gd.addCheckbox("Process Stack", false);
	String[] bones = {"unknown", "scapula", "humerus", "radius", "ulna", "metacarpal", "pelvis", "femur", "tibia", "fibula", "metatarsal"};
	//guess bone from image title
	String title = this.imp.getTitle();
	this.boneID = 0;
	for (int n = 0; n < bones.length; n++){
	    Pattern p = Pattern.compile(bones[n], Pattern.CASE_INSENSITIVE);
	    Matcher m = p.matcher(title);
	    if (m.find()){
		this.boneID = n;
		continue;
	    }
	}
	gd.addChoice("Bone: ", bones, bones[this.boneID]);
	String[] analyses = {"Weighted", "Unweighted", "Both"};
	gd.addChoice("Calculate: ", analyses, analyses[1]);
	//	gd.addNumericField("Voxel Size (x): ", vW, 3, 8, units);
	//	gd.addNumericField("Voxel Size (y): ", vH, 3, 8, units);
	//	gd.addNumericField("Voxel Size (z): ", vD, 3, 8, units);
	//	gd.addMessage(this.calString);
	gd.addMessage("Set the threshold");
	gd.addNumericField("Air:", this.airHU, 0);
	gd.addNumericField("Bone Min:", this.minBoneHU, 0);
	gd.addNumericField("Bone Max:", this.maxBoneHU, 0);
	gd.showDialog();
	this.doThickness = gd.getNextBoolean();
	this.doAxes = gd.getNextBoolean();
	this.doCentroids = gd.getNextBoolean();
	this.doOutline = gd.getNextBoolean();
	this.doCopy = gd.getNextBoolean();
	this.doStack = gd.getNextBoolean();
	String bone = gd.getNextChoice();
	for (int n = 0; n < bones.length; n++){
	    if(bone.equals(bones[n])) {
		this.boneID = n;
		continue;
	    }
	}
	this.analyse = gd.getNextChoice();
	//	this.vW = gd.getNextNumber();
	//	this.vH = gd.getNextNumber();
	//	this.vD = gd.getNextNumber();
	this.airHU = gd.getNextNumber();
	this.minBoneHU = gd.getNextNumber();
	this.maxBoneHU = gd.getNextNumber();
	if(gd.wasCanceled()){
	    return false;
	} else {
	    return true;
	}
    }

    private void showSliceResults(){
	ResultsTable rt = ResultsTable.getResultsTable();
	rt.reset();

	//TODO fix spatial calibration: this assumes isotropic pixels
	double unit4 = Math.pow(vW, 4);
	double unit3 = Math.pow(vW, 3);
	for (int s = 1; s <= this.stack.getSize(); s++) {
	    rt.incrementCounter();
	    rt.addValue("Slice", s);
	    rt.addValue("X cent. ("+units+")", this.sliceCentroids[0][s]);
	    rt.addValue("Y cent. ("+units+")", this.sliceCentroids[1][s]);
	    rt.addValue("Theta (rad)", theta[s]);
	    //	    rt.addValue("CA ("+units+"^2)", cortArea[s]);
	    rt.addValue("Imin ("+units+"^4)", Imin[s]*unit4);
	    rt.addValue("IminFast ("+units+"^4)", IminFast[s]*unit4);
	    rt.addValue("Imax ("+units+"^4)", Imax[s]*unit4);
	    rt.addValue("ImaxFast ("+units+"^4)", ImaxFast[s]*unit4);
	    rt.addValue("Ipm ("+units+"^4)", Ipm[s]*unit4);
	    rt.addValue("R1 ("+units+")", R1[s]);
	    rt.addValue("R2 ("+units+")", R2[s]);
	    rt.addValue("Zmax ("+units+"^3)", Zmax[s]*unit3);
	    rt.addValue("Zmin ("+units+"^3)", Zmin[s]*unit3);
	}
	rt.show("Results");
    }
    
    private void roiMeasurements(){
	//for the required slices...
	
	//generate an ROI, e.g. with the wand
	
	Rotating_Calipers rc = new Rotating_Calipers();
	double dMin = rc.rotatingCalipers(this.imp);
	
	//get the Feret diameter
	//TODO feret diameter 
    }
}