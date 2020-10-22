/*
 *  Copyright University of Basel, Graphics and Vision Research Group
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package api.sampling.proposals

import java.io.File

import api.other.{DoubleProjectionSampling, IcpProjectionDirection, ModelSampling, TargetSampling}
import api.sampling.{ModelFittingParameters, ShapeParameters, SurfaceNoiseHelpers}
import apps.teeth.utilities.Paths.generalPath
import breeze.stats.distributions.Uniform
import scalismo.common.{Field, NearestNeighborInterpolator, PointId}
import scalismo.geometry._
import scalismo.io.LandmarkIO
import scalismo.mesh.{TriangleMesh, TriangleMesh3D}
import scalismo.numerics.UniformMeshSampler3D
import scalismo.registration.RigidTransformation
import scalismo.sampling.{ProposalGenerator, TransitionProbability}
import scalismo.statisticalmodel.{LowRankGaussianProcess, MultivariateNormalDistribution, StatisticalMeshModel}
import scalismo.utils.Memoize

case class NonRigidIcpProposal(
                                model: StatisticalMeshModel,
                                target: TriangleMesh3D,
                                stepLength: Double,
                                tangentialNoise: Double,
                                noiseAlongNormal: Double,
                                numOfSamplePoints: Int,
                                projectionDirection: IcpProjectionDirection = ModelSampling,
                                boundaryAware: Boolean = true,
                                generatedBy: String = "ShapeIcpProposal"
                              )(
                                implicit rand: scalismo.utils.Random
                              ) extends ProposalGenerator[ModelFittingParameters]
  with TransitionProbability[ModelFittingParameters] {
  private val middlept = LandmarkIO.readLandmarksJson[_3D](new File(generalPath + "/Registered/model/pcaModel_1000points_Landmarks_middle.json")).get

  private val referenceMesh = model.referenceMesh

  private val cashedPosterior: Memoize[ModelFittingParameters, LowRankGaussianProcess[_3D, EuclideanVector[_3D]]] = Memoize(icpPosterior, 20)

  private lazy val interpolatedModel = model.gp.interpolate(NearestNeighborInterpolator())

  private val modelPoints = UniformMeshSampler3D(model.referenceMesh, numOfSamplePoints).sample().map(_._1)
  private val modelIds: IndexedSeq[PointId] = modelPoints.map(p => model.referenceMesh.pointSet.findClosestPoint(p).id).toIndexedSeq
  private val targetPoints: IndexedSeq[Point[_3D]] = UniformMeshSampler3D(target, numOfSamplePoints).sample().map(_._1).toIndexedSeq

  override def propose(theta: ModelFittingParameters): ModelFittingParameters = {
    val posterior = cashedPosterior(theta)
    val proposed: Field[_3D, EuclideanVector[_3D]] = posterior.sample()

    def f(pt: Point[_3D]): Point[_3D] = pt + proposed(pt)

    val newCoefficients = model.coefficients(referenceMesh.transform(f))

    val currentShapeCoefficients = theta.shapeParameters.parameters
    val newShapeCoefficients = currentShapeCoefficients + (newCoefficients - currentShapeCoefficients) * stepLength

    theta.copy(
      shapeParameters = ShapeParameters(newShapeCoefficients),
      generatedBy = generatedBy
    )
  }


  private def randomStepLength(theta: ModelFittingParameters): Double = {
    1 - scala.util.Random.nextDouble() * 2.0
  }


  override def logTransitionProbability(from: ModelFittingParameters, to: ModelFittingParameters): Double = {
    val posterior = cashedPosterior(from)

    val compensatedTo = to.copy(shapeParameters = ShapeParameters(from.shapeParameters.parameters + (to.shapeParameters.parameters - from.shapeParameters.parameters) / stepLength))

    val pdf = posterior.logpdf(compensatedTo.shapeParameters.parameters)
    pdf
  }

  private def icpPosterior(theta: ModelFittingParameters): LowRankGaussianProcess[_3D, EuclideanVector[_3D]] = {
    def modelBasedClosestPointsEstimation(
                                           currentMesh: TriangleMesh[_3D],
                                           inversePoseTransform: RigidTransformation[_3D]
                                         ): IndexedSeq[(Point[_3D], EuclideanVector[_3D], MultivariateNormalDistribution)] = {

      //      val modelPoints = UniformMeshSampler3D(model.referenceMesh, numOfSamplePoints).sample().map(_._1)
      //      val modelIds: IndexedSeq[PointId] = modelPoints.map(p => model.referenceMesh.pointSet.findClosestPoint(p).id)

      val noisyCorrespondence = modelIds.map {
        id =>
          val currentMeshPoint = currentMesh.pointSet.point(id)
          val targetPoint = target.operations.closestPointOnSurface(currentMeshPoint).point
          val targetPointId = target.pointSet.findClosestPoint(targetPoint).id
          val isOnBoundary = target.operations.pointIsOnBoundary(targetPointId)
          val noiseDistribution = SurfaceNoiseHelpers.surfaceNormalDependantNoise(currentMesh.vertexNormals.atPoint(id), noiseAlongNormal, tangentialNoise)
          (id, targetPoint, noiseDistribution, isOnBoundary)
      }

      val correspondenceFiltered = if (boundaryAware) noisyCorrespondence.filter(!_._4) else noisyCorrespondence

      for ((pointId, targetPoint, uncertainty, _) <- correspondenceFiltered) yield {
        val referencePoint = model.referenceMesh.pointSet.point(pointId)
        (referencePoint, inversePoseTransform(targetPoint) - referencePoint, uncertainty)
      }
    }


    def targetBasedClosestPointsEstimation(
                                            currentMesh: TriangleMesh[_3D],
                                            inversePoseTransform: RigidTransformation[_3D]
                                          ): IndexedSeq[(Point[_3D], EuclideanVector[_3D], MultivariateNormalDistribution)] = {

      //      val targetPoints: Seq[Point[_3D]] = UniformMeshSampler3D(target, numOfSamplePoints).sample().map(_._1).toIndexedSeq

      val noisyCorrespondence = targetPoints.map { targetPoint =>
        val id = currentMesh.pointSet.findClosestPoint(targetPoint).id
        val isOnBoundary = currentMesh.operations.pointIsOnBoundary(id)
        val noiseDistribution = SurfaceNoiseHelpers.surfaceNormalDependantNoise(currentMesh.vertexNormals.atPoint(id), noiseAlongNormal, tangentialNoise)
        (id, targetPoint, noiseDistribution, isOnBoundary)
      }

      val correspondenceFiltered = if (boundaryAware) noisyCorrespondence.filter(!_._4) else noisyCorrespondence

      for ((pointId, targetPoint, uncertainty, _) <- correspondenceFiltered) yield {
        val referencePoint = model.referenceMesh.pointSet.point(pointId)
        // (reference point, deformation vector in model space starting from reference, usually zero-mean observation uncertainty)
        (referencePoint, inversePoseTransform(targetPoint) - referencePoint, uncertainty)
      }
    }
    def samplePtIds(mesh: TriangleMesh[_3D],points : IndexedSeq[Point[_3D]] ,numberOfPoints : Int):IndexedSeq[(Point[_3D],Double)] = {
      val p = 1.0 / mesh.area

      val distrDim1 = Uniform(0, mesh.pointSet.numberOfPoints)(rand.breezeRandBasis)
      val pts = (0 until numberOfPoints).map(i => (points(distrDim1.draw().toInt), p))
      pts
    }
    def doubleProjectionBasedClosestPointsEstimation(
                                            currentMesh: TriangleMesh[_3D],
                                            inversePoseTransform: RigidTransformation[_3D]
                                          ): IndexedSeq[(Point[_3D], EuclideanVector[_3D], MultivariateNormalDistribution)] = {


      val id3 = currentMesh.pointSet.findClosestPoint(middlept.seq(0).point).id
      val modelIds = currentMesh.pointSet.pointIds.filter { id =>

        (currentMesh.pointSet.point(id) - currentMesh.pointSet.point(id3)).norm <= 5.5
      }
      val modelPtIds = modelIds.map(id => currentMesh.pointSet.point(id)).toIndexedSeq
      val sampledPts = samplePtIds(model.referenceMesh, modelPtIds,numberOfPoints = numOfSamplePoints)
      //val modelPoints: IndexedSeq[Point[_3D]] = sampler.sample.map(pointWithProbability => pointWithProbability._1) // we only want the points

      val correspondences2 = sampledPts.map {
        pt =>
          val currentMeshPoint = currentMesh.pointSet.findClosestPoint(pt._1).point
          val currentMeshid = currentMesh.pointSet.findClosestPoint(pt._1).id
          val targetPoint = target.operations.closestPointOnSurface(currentMeshPoint).point
          val targetPointId = target.pointSet.findClosestPoint(targetPoint).id
          val isOnBoundary = target.operations.pointIsOnBoundary(targetPointId)
          val noiseDistribution = SurfaceNoiseHelpers.surfaceNormalDependantNoise(currentMesh.vertexNormals.atPoint(currentMeshid), noiseAlongNormal, tangentialNoise)
          (currentMeshid, targetPoint, noiseDistribution, isOnBoundary)
      }

      val correspondenceFiltered2 = correspondences2.filter(!_._4)


      for ((pointId, targetPoint, uncertainty, _) <- correspondenceFiltered2) yield {
        val referencePoint = currentMesh.pointSet.point(pointId)
        (referencePoint, inversePoseTransform(targetPoint) - referencePoint, uncertainty)
      }

    }

    /**
      * Estimate where points should move to together with a surface normal dependant noise.
      *
      * @param theta Current fitting parameters
      * @return List of points, with associated deformation and normal dependant surface noise.
      */
    def uncertainDisplacementEstimation(theta: ModelFittingParameters)
    : IndexedSeq[(Point[_3D], EuclideanVector[_3D], MultivariateNormalDistribution)] = {
      val currentMesh = ModelFittingParameters.transformedMesh(model, theta)
      val inversePoseTransform = ModelFittingParameters.poseTransform(theta).inverse

      if (projectionDirection == TargetSampling) {
        targetBasedClosestPointsEstimation(currentMesh, inversePoseTransform)
      } else if (projectionDirection == DoubleProjectionSampling){
        doubleProjectionBasedClosestPointsEstimation(currentMesh, inversePoseTransform)
      } else {
        modelBasedClosestPointsEstimation(currentMesh, inversePoseTransform)
      }
    }

    val uncertainDisplacements = uncertainDisplacementEstimation(theta)
    interpolatedModel.posterior(uncertainDisplacements)
  }

}